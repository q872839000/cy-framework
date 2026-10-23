// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.Events;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段链引擎：固定阶段链的顺序编排（Graph/Pipeline 形态的执行内核）。
 * <p>
 * 引擎托管的部分（领域无关）：阶段查找与分发、阶段级步骤事件、回退预算、
 * 转移熔断、取消检查点。阶段语义（每个阶段做什么、何时跳转 / 回退 / 完）
 * 由 {@link StageHandler} 表达，经 {@link StageHandlerRegistry} 注入。
 * </p>
 * <p>
 * <b>计数可恢复</b>：转移 / 回退计数经 {@link PipelineAttributes} 存于运行上下文属性，
 * 挂起（Awaiting）后宿主重新调 {@code run()} 时从属性恢复累计值，预算不被重新武装；
 * 链到达终态（Done / Failed / 熔断等）时清零，防同上下文复用残留。
 * </p>
 * <p>
 * 与 {@code ReactLoopEngine} 的关系：两引擎平级，宿主按意图路由选择；
 * {@link StageOutcome.SwitchEngine} 提供链 → 循环的切换通道（保留已产出进展、
 * 携带切换载荷由宿主转发给目标引擎）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class PipelineEngine {

    private static final Logger log = LoggerFactory.getLogger(PipelineEngine.class);

    /**
     * 运行阶段链（计数自上下文属性恢复，支持挂起后续跑）。
     *
     * @param ctx          运行上下文
     * @param initialStage 起始阶段标识
     * @param registry     阶段处理器注册表
     * @param config       链配置
     * @return 链执行结果
     * @throws RunCancellationException 运行被取消
     */
    public PipelineOutcome run(AgentRunContext ctx, String initialStage,
                               StageHandlerRegistry registry, PipelineConfig config) {
        String current = initialStage;
        int transitions = PipelineAttributes.restoreTransitions(ctx);
        int rollbacks = PipelineAttributes.restoreRollbacks(ctx);
        while (true) {
            throwIfCancelled(ctx, "pipeline_" + current);
            if (transitions >= config.maxTransitions()) {
                PipelineAttributes.clear(ctx);
                return fuseTransitions(ctx, transitions, rollbacks);
            }
            StageHandler handler = registry.lookup(current);
            if (handler == null) {
                PipelineAttributes.clear(ctx);
                return missingHandler(current, transitions, rollbacks);
            }
            int stepIndex = ctx.nextStepIndex();
            Events.stepStarted(ctx.getBus(), stepIndex, Map.of("stage", current));
            StageOutcome outcome;
            try {
                outcome = handler.handle(ctx);
            } finally {
                Events.stepFinished(ctx.getBus(), stepIndex, true, Map.of("stage", current));
            }
            PipelineOutcome terminal = dispatch(ctx, outcome, transitions, rollbacks);
            if (terminal != null) {
                // 仅真终态清预算计数：挂起类结果（Awaiting / SwitchEngine）运行未结束，
                // 计数留在属性表供重入 run() 时延续累计（预算不重新武装，熔断保护跨挂起有效）。
                if (!terminal.suspending()) {
                    PipelineAttributes.clear(ctx);
                }
                return terminal;
            }
            if (outcome instanceof StageOutcome.Continue next) {
                transitions++;
                PipelineAttributes.stashTransitions(ctx, transitions);
                current = next.nextStage();
            } else if (outcome instanceof StageOutcome.Rollback rollback) {
                rollbacks++;
                transitions++;
                PipelineAttributes.stashTransitions(ctx, transitions);
                PipelineAttributes.stashRollbacks(ctx, rollbacks);
                if (rollbacks > config.maxRollbacks()) {
                    PipelineAttributes.clear(ctx);
                    return fuseRollbacks(ctx, rollbacks, transitions);
                }
                log.info("阶段回退 runId={} from={} to={} reason={}",
                        ctx.getRunId(), current, rollback.targetStage(), rollback.reason());
                Events.systemTrace(ctx.getBus(), "pipeline.rollback", "回退到 " + rollback.targetStage(),
                        Map.of("from", current, "to", rollback.targetStage(), "reason", rollback.reason()));
                current = rollback.targetStage();
            } else if (outcome instanceof StageOutcome.ContinueLoop) {
                // 短路跳转：不改阶段重新执行当前阶段；计入转移数，保证转移熔断可触发。
                transitions++;
                PipelineAttributes.stashTransitions(ctx, transitions);
            }
        }
    }

    /**
     * 终态类流转分发（Continue / Rollback / ContinueLoop 之外的六种终态）。
     *
     * @param ctx         运行上下文
     * @param outcome     阶段结果
     * @param transitions 当前转移数
     * @param rollbacks   当前回退数
     * @return 链终态结果；非终态流转返回 null
     */
    private PipelineOutcome dispatch(AgentRunContext ctx, StageOutcome outcome,
                                     int transitions, int rollbacks) {
        if (outcome instanceof StageOutcome.Done done) {
            return PipelineOutcome.done(done.message(), transitions, rollbacks);
        }
        if (outcome instanceof StageOutcome.Failed failed) {
            return PipelineOutcome.failed(failed.code(), failed.message(), failed.retryable(),
                    transitions, rollbacks);
        }
        if (outcome instanceof StageOutcome.Awaiting awaiting) {
            // 挂起不清计数：宿主恢复后重新进入 run()，从属性继续累计（预算不重新武装）。
            return PipelineOutcome.awaiting(awaiting.message(), awaiting.awaitingStage(),
                    transitions, rollbacks);
        }
        if (outcome instanceof StageOutcome.AskUserDecision decision) {
            return PipelineOutcome.askUser(decision.question(), decision.options(),
                    transitions, rollbacks);
        }
        if (outcome instanceof StageOutcome.SwitchEngine sw) {
            log.info("引擎切换 runId={} target={} reason={}", ctx.getRunId(), sw.targetEngine(), sw.reason());
            return PipelineOutcome.switchEngine(sw.targetEngine(), sw.handoffPayload(),
                    transitions, rollbacks);
        }
        return null;
    }

    /**
     * 阶段处理器缺失的失败结果。
     *
     * @param stage       阶段标识
     * @param transitions 转移数
     * @param rollbacks   回退数
     * @return 失败结果
     */
    private PipelineOutcome missingHandler(String stage, int transitions, int rollbacks) {
        RuntimeErrorCode code = RuntimeErrorCode.PIPELINE_STAGE_MISSING;
        return PipelineOutcome.failed(code.code(), code.format(stage), code.retryable(),
                transitions, rollbacks);
    }

    /**
     * 转移熔断。
     *
     * @param ctx         运行上下文
     * @param transitions 转移数
     * @param rollbacks   回退数
     * @return 熔断结果
     */
    private PipelineOutcome fuseTransitions(AgentRunContext ctx, int transitions, int rollbacks) {
        RuntimeErrorCode code = RuntimeErrorCode.PIPELINE_TRANSITIONS_EXCEEDED;
        log.warn("阶段链转移熔断 runId={} transitions={}", ctx.getRunId(), transitions);
        Events.systemTrace(ctx.getBus(), "pipeline.transitions_fuse", "阶段转移次数超限",
                Map.of("transitions", transitions));
        return PipelineOutcome.fused(code.code(), code.format(transitions),
                PipelineOutcome.Status.TRANSITIONS_EXCEEDED, transitions, rollbacks);
    }

    /**
     * 回退预算熔断。
     *
     * @param ctx         运行上下文
     * @param rollbacks   回退数
     * @param transitions 转移数
     * @return 熔断结果
     */
    private PipelineOutcome fuseRollbacks(AgentRunContext ctx, int rollbacks, int transitions) {
        RuntimeErrorCode code = RuntimeErrorCode.PIPELINE_ROLLBACKS_EXCEEDED;
        log.warn("阶段链回退熔断 runId={} rollbacks={}", ctx.getRunId(), rollbacks);
        Events.systemTrace(ctx.getBus(), "pipeline.rollbacks_fuse", "阶段回退次数超预算",
                Map.of("rollbacks", rollbacks));
        return PipelineOutcome.fused(code.code(), code.format(rollbacks),
                PipelineOutcome.Status.ROLLBACKS_EXCEEDED, transitions, rollbacks);
    }

    /**
     * 取消检查点。
     *
     * @param ctx   运行上下文
     * @param phase 检查点标识
     * @throws RunCancellationException 已取消
     */
    private void throwIfCancelled(AgentRunContext ctx, String phase) {
        if (ctx.isCancelled()) {
            throw new RunCancellationException(ctx.getRunId(), phase);
        }
    }
}
