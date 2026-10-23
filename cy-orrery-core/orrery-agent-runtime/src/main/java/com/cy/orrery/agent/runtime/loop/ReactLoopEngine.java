// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.harness.ContextGuard;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.plan.LedgerProgressMatcher;
import com.cy.orrery.agent.runtime.plan.LedgerReconciler;
import com.cy.orrery.agent.runtime.plan.PlanLedger;
import com.cy.orrery.agent.runtime.plan.PlanLedgerAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 循环引擎：主循环编排（思考 → 工具 → 观察 → 迭代）。
 * <p>
 * 引擎托管的部分（领域无关）：轮次调度、取消检查点、上下文守望接入、
 * 审批暂缓重放、收敛熔断（连续零事实进展轮）、退化熔断（连续工具失败零进展轮）、
 * 轮次耗尽终局、小步走检测。领域语义经 {@link LoopRuntimeSpec} 注入：LLM 调用
 * （{@link LlmInvoker}）、无工具轮处理与终局结算（{@link LoopDomainSpec}）、
 * 工具轮执行由构造注入的 {@link LoopToolRoundEngine} 承担。
 * </p>
 * <p>
 * 事件契约：全部事件经 {@link Events} 工厂发射（data key 冻结）；审批挂起事件
 * 由 {@link LoopToolRoundEngine} 经钩子委托，本引擎不发审批事件。
 * </p>
 * <p>
 * 双熔断语义：收敛熔断按「业务动作零增长 且 领域状态签名不变」判零进展，
 * 达阈值走与轮次耗尽相同的终局结算（{@code settleTerminal}）；退化熔断按
 * 「本轮有工具失败 且 业务动作零增长」计连击，达阈值硬停（REPEATED_TOOL_FAILURE）
 * 并发澄清引导。任一轮有进展即清零。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class ReactLoopEngine {

    private static final Logger log = LoggerFactory.getLogger(ReactLoopEngine.class);

    private final LoopToolRoundEngine toolRoundEngine;
    private final MicroStepDetector microStepDetector;

    /**
     * 构造循环引擎（小步走检测默认启用）。
     *
     * @param toolRoundEngine 工具轮引擎
     */
    public ReactLoopEngine(LoopToolRoundEngine toolRoundEngine) {
        this(toolRoundEngine, new MicroStepDetector());
    }

    /**
     * 构造循环引擎。
     *
     * @param toolRoundEngine   工具轮引擎
     * @param microStepDetector 小步走检测器（传 null 关闭检测）
     */
    public ReactLoopEngine(LoopToolRoundEngine toolRoundEngine, MicroStepDetector microStepDetector) {
        this.toolRoundEngine = toolRoundEngine;
        this.microStepDetector = microStepDetector;
    }

    /**
     * 运行 ReAct 主循环：先重放审批暂缓调用（如存在），再进入轮次循环。
     * <p>
     * 每段 run 结束（含挂起早退）把本段累加器遥测累计进上下文属性
     * （跨挂起段累计，终态事件取多段之和）；取消异常路径不累计
     * （运行未正常收敛，续跑时该段轮次不占预算）。
     * </p>
     *
     * @param ctx  运行上下文
     * @param spec 运行规格
     * @return 循环结果
     * @throws RunCancellationException 运行被取消
     */
    public LoopOutcome run(AgentRunContext ctx, LoopRuntimeSpec spec) {
        LoopAccumulator acc = new LoopAccumulator();
        try {
            List<String> actionLog = LoopAttributes.actionLog(ctx);
            int actionLogBeforeReplay = actionLog.size();
            LoopToolRoundEngine.DeferredReplayOutcome replay =
                    toolRoundEngine.replayDeferredIfNeeded(ctx, acc, spec.hooks());
            if (replay != null && !applyReplayOutcome(acc, replay)) {
                return outcome(ctx, acc);
            }
            // 重放期间追加的业务动作同样要进账本勾稽窗口：否则挂起-恢复场景
            // 账本少推进、LEDGER_COMPLETED 提前收敛在重放路径失效
            reconcileLedger(ctx, spec, actionLogBeforeReplay, actionLog);
            return runMainLoop(ctx, spec, acc);
        } finally {
            if (!ctx.isCancelled()) {
                LoopAttributes.accumulateRunTotals(ctx, acc);
            }
        }
    }

    /**
     * 主循环体：轮次调度 + 双熔断。
     * <p>
     * 轮次预算跨挂起段累计（与 Pipeline 的转移 / 回退预算同语义）：预算取
     * {@code maxRounds - 既往段累计轮次}，反复「挂起→恢复」不得重新武装预算。
     * </p>
     *
     * @param ctx  运行上下文
     * @param spec 运行规格
     * @param acc  循环累加器
     * @return 循环结果
     */
    private LoopOutcome runMainLoop(AgentRunContext ctx, LoopRuntimeSpec spec, LoopAccumulator acc) {
        List<String> actionLog = LoopAttributes.actionLog(ctx);
        LoopConfig cfg = spec.config();
        RoundCounters counters = new RoundCounters();
        int budget = Math.max(0, cfg.maxRounds() - LoopAttributes.totalRounds(ctx));
        for (int i = 0; i < budget; i++) {
            throwIfCancelled(ctx, "react_round_" + i);
            LoopOutcome stop = processRound(ctx, spec, acc, actionLog, i, counters);
            if (stop != null) {
                return stop;
            }
        }
        spec.domainSpec().settleTerminal(ctx, acc, LoopDomainSpec.TerminalReason.ROUNDS_EXHAUSTED);
        return outcome(ctx, acc);
    }

    /** 跨轮计数载体（退化连击 / 空转连击 / 上一空转轮签名），主循环与各阶段子方法共享。 */
    private static final class RoundCounters {
        int failedStreak;
        int idleRounds;
        String lastIdleSignature;
    }

    /**
     * 处理单轮：LLM 调用 → 空转分支 / 工具分支。
     *
     * @param ctx        运行上下文
     * @param spec       运行规格
     * @param acc        循环累加器
     * @param actionLog  业务动作日志
     * @param roundIndex 轮次下标
     * @param counters   跨轮计数
     * @return 终止结果（null 表示继续下一轮）
     */
    private LoopOutcome processRound(AgentRunContext ctx, LoopRuntimeSpec spec, LoopAccumulator acc,
                                     List<String> actionLog, int roundIndex, RoundCounters counters) {
        prepareContext(ctx, spec.contextGuard());
        acc.rounds++;
        int stepIndex = ctx.nextStepIndex();
        Events.stepStarted(ctx.getBus(), stepIndex);
        boolean stepHadToolCalls = false;
        try {
            LlmInvoker.LlmRoundResult result = invokeRound(ctx, spec, acc, roundIndex);
            if (result == null) {
                return outcome(ctx, acc);
            }
            List<LoopToolCall> calls = result.toolCalls() == null ? List.of() : result.toolCalls();
            stepHadToolCalls = !calls.isEmpty();
            if (calls.isEmpty()) {
                return settleIdleRound(ctx, spec, acc, actionLog, result, counters);
            }
            counters.idleRounds = 0;
            return executeToolPhase(ctx, spec, acc, actionLog, calls, result, counters);
        } finally {
            Events.stepFinished(ctx.getBus(), stepIndex, stepHadToolCalls);
        }
    }

    /**
     * 执行一轮 LLM 调用：失败时应用失败语义并返回 null（调用方应终止）。
     *
     * @param ctx        运行上下文
     * @param spec       运行规格
     * @param acc        循环累加器
     * @param roundIndex 轮次下标
     * @return LLM 归一化结果；失败已处理时为 null
     */
    private LlmInvoker.LlmRoundResult invokeRound(AgentRunContext ctx, LoopRuntimeSpec spec,
                                                  LoopAccumulator acc, int roundIndex) {
        LlmInvoker invoker = spec.llmInvoker();
        LlmInvoker.LlmRoundResult result = invoker.invoke(ctx, roundIndex, invoker.toolSpecs());
        if (result.failed()) {
            applyLlmFailure(acc, result);
            return null;
        }
        if (result.stopHint() == AgentStop.OUTPUT_TRUNCATED) {
            // 截断到达引擎 = 无续接装饰器或续接失败（续接装饰器成功时会消掉 stopHint）：
            // 半截 tool_calls JSON 不可信，本轮全部工具调用丢弃不执行。
            // 宿主接管（onOutputTruncated 返回 true）时不武装失败终态——
            // 宿主以「澄清驱动的可续跑挂起」语义全责处理（暂存续跑态 + 发澄清）。
            if (spec.domainSpec().onOutputTruncated(ctx, acc, result)) {
                return null;
            }
            // 默认：按截断错误终态收敛。已流式下发的半截正文保留在终态结果里（可见但标注截断），
            // 兑现 AiFacadeLlmInvoker 契约"未装配续接装饰器时由引擎按截断语义终止"。
            RuntimeErrorCode code = RuntimeErrorCode.AGENT_OUTPUT_TRUNCATED;
            log.warn("LLM 输出截断终止 runId={} round={} toolCallsDropped={} contentLen={}",
                    ctx.getRunId(), roundIndex,
                    result.toolCalls() == null ? 0 : result.toolCalls().size(),
                    result.content() == null ? 0 : result.content().length());
            acc.stop = AgentStop.OUTPUT_TRUNCATED;
            acc.finalText = result.content();
            acc.errorMsg = code.format();
            acc.failedCode = code.code();
            acc.failedRetryable = code.retryable();
            return null;
        }
        accumulateRound(ctx, acc, result);
        return result;
    }

    /**
     * 空转轮结算：领域处理 + 零进展判定与收敛熔断推进，更新计数。
     *
     * @param ctx       运行上下文
     * @param spec      运行规格
     * @param acc       循环累加器
     * @param actionLog 业务动作日志
     * @param result    本轮 LLM 结果
     * @param counters  跨轮计数（原地更新 idleRounds / lastIdleSignature）
     * @return 终止结果（null 表示继续下一轮）
     */
    private LoopOutcome settleIdleRound(AgentRunContext ctx, LoopRuntimeSpec spec, LoopAccumulator acc,
                                        List<String> actionLog, LlmInvoker.LlmRoundResult result,
                                        RoundCounters counters) {
        IdleRoundResult idle = handleIdleRound(ctx, spec, acc, actionLog,
                counters.lastIdleSignature, counters.idleRounds, result);
        if (idle.terminated()) {
            return outcome(ctx, acc);
        }
        counters.idleRounds = idle.idleCount();
        counters.lastIdleSignature = spec.domainSpec().stateSignature(ctx);
        return null;
    }

    /**
     * 工具分支执行：小步走检测 → 工具轮 → 账本勾稽 → 提前收敛 → 退化熔断。
     *
     * @param ctx       运行上下文
     * @param spec      运行规格
     * @param acc       循环累加器
     * @param actionLog 业务动作日志
     * @param calls     本轮 tool_calls
     * @param result    本轮 LLM 结果
     * @param counters  跨轮计数（原地更新 failedStreak）
     * @return 终止结果（null 表示继续下一轮）
     */
    private LoopOutcome executeToolPhase(AgentRunContext ctx, LoopRuntimeSpec spec, LoopAccumulator acc,
                                         List<String> actionLog, List<LoopToolCall> calls,
                                         LlmInvoker.LlmRoundResult result, RoundCounters counters) {
        LoopToolRoundEngine.ToolRoundResult roundResult = toolRoundEngine
                .executeToolCallsRound(ctx, acc, calls, spec.hooks());
        // 小步走提示必须在工具轮回执挂载之后注入：user 消息插进 assistant.tool_calls
        // 与其 tool 回执之间会破坏 OpenAI 协议配对（严格厂商直接 400）
        if (microStepDetector != null) {
            microStepDetector.nudgeIfMicroStepping(ctx, calls, result,
                    spec.domainSpec()::microStepMergeNudge);
        }
        if (roundResult.suspend()) {
            return outcome(ctx, acc);
        }
        reconcileLedger(ctx, spec, roundResult.actionLogSizeBefore(), actionLog);
        if (maybeSettleOnLedgerComplete(ctx, spec, acc)) {
            return outcome(ctx, acc);
        }
        counters.failedStreak = updateDegradationCount(counters.failedStreak,
                roundResult, actionLog.size());
        if (counters.failedStreak >= spec.config().maxConsecutiveFailedRounds()) {
            triggerDegradationFuse(ctx, acc, actionLog, spec);
            return outcome(ctx, acc);
        }
        return null;
    }

    /** 空转轮处理结果：是否终止 + 更新后的连续空转计数。 */
    private record IdleRoundResult(boolean terminated, int idleCount) {

        static IdleRoundResult terminate() {
            return new IdleRoundResult(true, 0);
        }

        static IdleRoundResult counting(int count) {
            return new IdleRoundResult(false, count);
        }
    }

    /**
     * 无工具调用轮处理：先交领域处理（pre-Final 校验 / 终局判定），再做零进展判定与收敛熔断推进。
     *
     * @param ctx                   运行上下文
     * @param spec                  运行规格
     * @param acc                   循环累加器
     * @param actionLog             业务动作日志
     * @param lastIdleSignature     上一空转轮状态签名
     * @param consecutiveIdleRounds 当前连续空转轮数
     * @param result                本轮 LLM 结果
     * @return 空转轮处理结果（terminated=true 表示循环应终止）
     */
    private IdleRoundResult handleIdleRound(AgentRunContext ctx, LoopRuntimeSpec spec,
                                            LoopAccumulator acc, List<String> actionLog,
                                            String lastIdleSignature, int consecutiveIdleRounds,
                                            LlmInvoker.LlmRoundResult result) {
        int actionLogBefore = actionLog.size();
        if (spec.domainSpec().handleNoToolCallRound(ctx, acc, result)) {
            fallbackFinalText(acc, result);
            return IdleRoundResult.terminate();
        }
        int next = advanceIdleFuse(ctx, spec, acc, actionLog, actionLogBefore,
                lastIdleSignature, consecutiveIdleRounds);
        return next < 0 ? IdleRoundResult.terminate() : IdleRoundResult.counting(next);
    }

    /**
     * 零进展判定与收敛熔断推进。
     * <p>
     * 进展判定（数据驱动）：业务动作有增长，或领域状态签名与上一空转轮不同。
     * 领域不提供签名（null）时仅按动作增长判定。达阈值触发收敛熔断，
     * 走与轮次耗尽相同的终局结算路径（终态语义写入主累加器）。
     * </p>
     *
     * @param ctx                   运行上下文
     * @param spec                  运行规格
     * @param acc                   循环累加器（熔断终态写入目标）
     * @param actionLog             业务动作日志
     * @param actionLogBefore       本轮开始前动作日志大小
     * @param lastIdleSignature     上一空转轮状态签名
     * @param consecutiveIdleRounds 当前连续空转轮数
     * @return 更新后的连续空转轮数；触发熔断时返回 -1
     */
    private int advanceIdleFuse(AgentRunContext ctx, LoopRuntimeSpec spec, LoopAccumulator acc,
                                List<String> actionLog, int actionLogBefore,
                                String lastIdleSignature, int consecutiveIdleRounds) {
        String signature = spec.domainSpec().stateSignature(ctx);
        boolean progressed;
        if (signature == null) {
            progressed = actionLog.size() > actionLogBefore;
        } else {
            progressed = actionLog.size() > actionLogBefore
                    || !signature.equals(lastIdleSignature);
        }
        if (progressed) {
            return 0;
        }
        int next = consecutiveIdleRounds + 1;
        if (next < spec.config().maxConsecutiveIdleRounds()) {
            return next;
        }
        log.info("触发收敛熔断 runId={} idleRounds={}", ctx.getRunId(), next);
        Events.systemTrace(ctx.getBus(), "agent.idle_fuse",
                "连续 " + next + " 轮无事实进展，按终态语义收敛结算",
                Map.of("idleRounds", next));
        spec.domainSpec().settleTerminal(ctx, acc, LoopDomainSpec.TerminalReason.IDLE_FUSE);
        return -1;
    }

    /**
     * 折叠循环结果（动作日志快照自上下文属性）。
     *
     * @param ctx 运行上下文
     * @param acc 循环累加器
     * @return 循环结果
     */
    private LoopOutcome outcome(AgentRunContext ctx, LoopAccumulator acc) {
        return new LoopOutcome(acc.rounds, acc.toolCallCount, acc.promptTokens, acc.completionTokens,
                acc.stop, acc.finalText, acc.errorMsg, List.copyOf(LoopAttributes.actionLog(ctx)),
                acc.failedCode, acc.failedRetryable);
    }

    /**
     * 领域终止时终轮正文兜底（领域未显式设置时以本轮正文充当）。
     *
     * @param acc    循环累加器
     * @param result 本轮 LLM 结果
     */
    private void fallbackFinalText(LoopAccumulator acc, LlmInvoker.LlmRoundResult result) {
        if (acc.finalText == null) {
            acc.finalText = result.content();
        }
    }

    /**
     * 应用重放结果：挂起（审批 / 澄清）或致命失败返回 false（应终止），完成返回 true。
     *
     * @param acc    循环累加器
     * @param replay 重放结果
     * @return true 表示继续主循环
     */
    private boolean applyReplayOutcome(LoopAccumulator acc,
                                       LoopToolRoundEngine.DeferredReplayOutcome replay) {
        if (replay.stop() == AgentStop.NEEDS_APPROVAL) {
            acc.stop = replay.stop();
            return false;
        }
        if (replay.stop() == AgentStop.NEEDS_CLARIFICATION) {
            // 重放中触发澄清：与主路径同语义挂起（剩余暂缓调用已由工具轮引擎回存，
            // 澄清答复后重入 run 继续重放），不静默丢弃。
            acc.stop = AgentStop.NEEDS_CLARIFICATION;
            return false;
        }
        if (replay.failedCode() != null) {
            acc.stop = AgentStop.BUSINESS_ERROR;
            acc.errorMsg = replay.failedMsg();
            acc.failedCode = replay.failedCode();
            acc.failedRetryable = replay.failedRetryable();
            return false;
        }
        return true;
    }

    /**
     * 累计本轮 token 并挂载 assistant 消息。
     *
     * @param ctx    运行上下文
     * @param acc    循环累加器
     * @param result 本轮 LLM 结果
     */
    private void accumulateRound(AgentRunContext ctx, LoopAccumulator acc,
                                 LlmInvoker.LlmRoundResult result) {
        acc.promptTokens += result.promptTokens();
        acc.completionTokens += result.completionTokens();
        ctx.addMessage(LoopMessage.assistant(result.content(), result.reasoning(), result.toolCalls()));
    }

    /**
     * LLM 失败语义应用（stopHint 优先，缺省 LLM_ERROR；可重试标志由适配器表达）。
     *
     * @param acc    循环累加器
     * @param result 失败的 LLM 结果
     */
    private void applyLlmFailure(LoopAccumulator acc, LlmInvoker.LlmRoundResult result) {
        acc.stop = result.stopHint() == null ? AgentStop.LLM_ERROR : result.stopHint();
        acc.errorMsg = result.errorMessage();
        acc.failedCode = result.errorCode();
        acc.failedRetryable = result.failedRetryable();
    }

    /**
     * 退化熔断计数更新：本轮有工具失败且业务动作零增长则 +1，否则清零。
     *
     * @param current          当前连击数
     * @param roundResult      工具轮结果
     * @param actionLogSizeNow 当前动作日志大小
     * @return 更新后的连击数
     */
    private int updateDegradationCount(int current, LoopToolRoundEngine.ToolRoundResult roundResult,
                                       int actionLogSizeNow) {
        if (roundResult.roundHadToolFailure()
                && actionLogSizeNow == roundResult.actionLogSizeBefore()) {
            return current + 1;
        }
        return 0;
    }

    /**
     * 账本勾稽：按本轮新增业务动作推进账本条目并暂存新账本。
     * <p>
     * 未装配账本或领域未提供匹配器时零开销；有推进（revision 变化）时发进度事件。
     * </p>
     *
     * @param ctx             运行上下文
     * @param spec            运行规格
     * @param actionLogBefore 本轮开始前动作日志大小
     * @param actionLog       业务动作日志
     */
    private void reconcileLedger(AgentRunContext ctx, LoopRuntimeSpec spec,
                                 int actionLogBefore, List<String> actionLog) {
        PlanLedger ledger = PlanLedgerAttributes.ledger(ctx);
        if (ledger == null) {
            return;
        }
        LedgerProgressMatcher matcher = spec.domainSpec().ledgerProgressMatcher();
        if (matcher == null) {
            return;
        }
        List<String> newActions = actionLog.subList(
                Math.min(actionLogBefore, actionLog.size()), actionLog.size());
        Optional<PlanLedger> updated = LedgerReconciler.reconcile(ledger, newActions, matcher);
        if (updated.isPresent()) {
            PlanLedger next = updated.get();
            PlanLedgerAttributes.stash(ctx, next);
            Events.systemTrace(ctx.getBus(), "plan.ledger",
                    "任务账本推进：" + next.progressSummary(),
                    Map.of("revision", next.revision(),
                            "done", next.doneCount(),
                            "total", next.items().size(),
                            "remaining", next.remainingCount()));
            log.info("账本勾稽推进 runId={} revision={} {}", ctx.getRunId(),
                    next.revision(), next.progressSummary());
        }
    }

    /**
     * 账本全完成提前收敛：全部条目终态且领域允许时，跳过剩余轮次直接终局结算。
     *
     * @param ctx  运行上下文
     * @param spec 运行规格
     * @param acc  循环累加器
     * @return true 表示已按 LEDGER_COMPLETED 终局并应终止循环
     */
    private boolean maybeSettleOnLedgerComplete(AgentRunContext ctx, LoopRuntimeSpec spec,
                                                LoopAccumulator acc) {
        PlanLedger ledger = PlanLedgerAttributes.ledger(ctx);
        if (ledger == null || !ledger.allSettled()) {
            return false;
        }
        if (!spec.domainSpec().allowEarlySettlement(ctx)) {
            return false;
        }
        log.info("账本全部条目终态，提前收敛 runId={} {}", ctx.getRunId(), ledger.progressSummary());
        Events.systemTrace(ctx.getBus(), "plan.ledger_completed",
                "任务账本全部条目已终态，提前收敛", Map.of("revision", ledger.revision()));
        spec.domainSpec().settleTerminal(ctx, acc, LoopDomainSpec.TerminalReason.LEDGER_COMPLETED);
        return true;
    }

    /**
     * 触发退化熔断：硬停 + 致命失败 + 澄清引导。
     *
     * @param ctx       运行上下文
     * @param acc       循环累加器
     * @param actionLog 业务动作日志
     * @param spec      运行规格
     */
    private void triggerDegradationFuse(AgentRunContext ctx, LoopAccumulator acc,
                                        List<String> actionLog, LoopRuntimeSpec spec) {
        RuntimeErrorCode code = RuntimeErrorCode.REPEATED_TOOL_FAILURE;
        log.info("触发退化循环熔断 runId={}", ctx.getRunId());
        acc.errorMsg = code.format();
        acc.stop = AgentStop.BUSINESS_ERROR;
        acc.failedCode = code.code();
        acc.failedRetryable = code.retryable();
        if (spec.domainSpec().degradationFuseEmitsClarification()) {
            Events.clarification(ctx.getBus(),
                    spec.domainSpec().degradationFuseGuidance(actionLog),
                    spec.domainSpec().degradationFuseOptions());
        }
        Events.systemTrace(ctx.getBus(), "agent.degradation_fuse",
                "连续多轮工具失败且无业务进展，已熔断");
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

    /**
     * 上下文守望接入（守望器可空）。
     *
     * @param ctx          运行上下文
     * @param contextGuard 守望器（可空）
     */
    private void prepareContext(AgentRunContext ctx, ContextGuard contextGuard) {
        if (contextGuard != null) {
            contextGuard.prepareForLlmCall(ctx);
        }
    }
}
