// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审批暂缓调用的确定性重放器（从 {@link LoopToolRoundEngine} 抽出的重放职责）。
 * <p>
 * 与主路径对称：重放中的调用同样过审批 / 澄清检查——再命中审批继续暂缓剩余调用，
 * 触发澄清则挂起 NEEDS_CLARIFICATION（剩余调用回存，答复后继续重放），均不静默丢弃。
 * </p>
 *
 * @author pcy
 * @since 2026/09/02
 */
final class DeferredReplayer {

    private static final Logger log = LoggerFactory.getLogger(DeferredReplayer.class);

    private final ToolInvoker toolInvoker;
    private final ToolCallInspector inspector;
    private final ToolRoundCodec codec;

    DeferredReplayer(ToolInvoker toolInvoker, ToolCallInspector inspector, ToolRoundCodec codec) {
        this.toolInvoker = toolInvoker;
        this.inspector = inspector;
        this.codec = codec;
    }

    /**
     * 重放审批屏障暂缓的兄弟调用（无暂缓时返回 {@code null}）。
     *
     * @param ctx   运行上下文
     * @param acc   循环累加器
     * @param hooks 领域钩子
     * @return 重放结果；无暂缓调用时为 null
     */
    LoopToolRoundEngine.DeferredReplayOutcome replayIfNeeded(AgentRunContext ctx,
                                                             LoopAccumulator acc,
                                                             ToolRoundHooks hooks) {
        List<DeferredToolCall> deferred = LoopAttributes.deferredCalls(ctx);
        if (deferred.isEmpty()) {
            return null;
        }
        RuntimeEventBus bus = ctx.getBus();
        int stepIndex = ctx.nextStepIndex();
        Events.stepStarted(bus, stepIndex, Map.of("replay", true));
        try {
            LoopToolRoundEngine.DeferredReplayOutcome outcome = replayCalls(ctx, acc, hooks, deferred);
            if (outcome.stop() == null || outcome.failedCode() != null) {
                // 重放完成或致命失败：原暂缓调用已消费，清空避免下轮重复重放；
                // 再次命中审批（NEEDS_APPROVAL）的 stillDeferred 由 settleReplay 回存，此处不清。
                LoopAttributes.clearDeferredCalls(ctx);
                LoopAttributes.clearPendingApprovals(ctx);
            }
            return outcome;
        } finally {
            Events.stepFinished(bus, stepIndex, true, Map.of("replay", true));
        }
    }

    /**
     * 确定性重放暂缓调用：按原参数逐个执行，真实回执按 callId 覆盖占位回执。
     *
     * @param ctx      运行上下文
     * @param acc      循环累加器
     * @param hooks    领域钩子
     * @param deferred 待重放调用
     * @return 重放结果
     */
    private LoopToolRoundEngine.DeferredReplayOutcome replayCalls(AgentRunContext ctx,
                                                                  LoopAccumulator acc,
                                                                  ToolRoundHooks hooks,
                                                                  List<DeferredToolCall> deferred) {
        RuntimeEventBus bus = ctx.getBus();
        List<String> actionLog = LoopAttributes.actionLog(ctx);
        List<ApprovalRequest> pendingApprovals = new ArrayList<>();
        List<DeferredToolCall> stillDeferred = new ArrayList<>();
        boolean barrierHit = false;
        boolean clarificationHit = false;
        FailureTally tally = new FailureTally();
        tally.totalCalls = deferred.size();
        int toolCalls = 0;
        for (int i = 0; i < deferred.size(); i++) {
            DeferredToolCall dc = deferred.get(i);
            if (barrierHit) {
                stillDeferred.add(dc);
                continue;
            }
            toolCalls++;
            acc.toolCallCount++;
            ToolCallResult exec;
            try {
                exec = toolInvoker.invoke(dc.callId(), dc.toolName(), dc.arguments(), ctx);
            } catch (FatalToolCallException fte) {
                log.warn("致命工具错误（重放） runId={} tool={} code={}",
                        ctx.getRunId(), dc.toolName(), fte.errorCode());
                tally.recordFatal(fte.errorCode(), fte.getMessage(), fte.retryable());
                ctx.replaceOrAddToolMessage(dc.callId(), fte.getMessage());
                if (tally.totalCalls == 1) {
                    hooks.onRoundSettled(ctx, false);
                    return LoopToolRoundEngine.DeferredReplayOutcome.failed(toolCalls,
                            fte.errorCode(), fte.getMessage(), fte.retryable());
                }
                continue;
            }
            if (exec.success()) {
                tally.successCount++;
            }
            ctx.replaceOrAddToolMessage(dc.callId(), codec.serialize(exec));
            ApprovalRequest approval = inspector.inspectApproval(exec, ctx);
            if (exec.success() && approval == null) {
                hooks.onToolCallSucceeded(exec, dc.arguments(), actionLog);
            }
            if (approval != null && approval.approvalId() != null
                    && !approval.approvalId().isBlank()) {
                pendingApprovals.add(approval);
                barrierHit = true;
                continue;
            }
            ClarificationRequest clarification = inspector.inspectClarification(exec, ctx);
            if (clarification != null) {
                // 澄清挂起（与主路径对称）：当前调用已执行（回执已覆盖），剩余调用回存
                Events.clarification(bus, clarification.question(), clarification.options());
                clarificationHit = true;
                barrierHit = true;
            }
        }
        if (clarificationHit) {
            // 引发本轮重放的旧审批已全部决定：清掉旧待审批，剩余调用回存待澄清答复后续跑
            LoopAttributes.clearPendingApprovals(ctx);
            LoopAttributes.stashDeferredCalls(ctx, stillDeferred);
            hooks.onRoundSettled(ctx, false);
            return LoopToolRoundEngine.DeferredReplayOutcome.of(toolCalls,
                    AgentStop.NEEDS_CLARIFICATION);
        }
        return settleReplay(ctx, hooks, pendingApprovals, stillDeferred, toolCalls, tally);
    }

    /**
     * 重放结算：全 fatal 中止 / 部分失败提示 / 再次审批挂起 / 正常完成。
     *
     * @param ctx              运行上下文
     * @param hooks            领域钩子
     * @param pendingApprovals 重放中新增的审批请求
     * @param stillDeferred    仍被屏障暂缓的调用
     * @param toolCalls        已执行数
     * @param tally            失败统计
     * @return 重放结果
     */
    private LoopToolRoundEngine.DeferredReplayOutcome settleReplay(AgentRunContext ctx,
                                                                   ToolRoundHooks hooks,
                                                                   List<ApprovalRequest> pendingApprovals,
                                                                   List<DeferredToolCall> stillDeferred,
                                                                   int toolCalls, FailureTally tally) {
        RuntimeEventBus bus = ctx.getBus();
        if (tally.allFatalNoSuccess() && pendingApprovals.isEmpty()) {
            hooks.onRoundSettled(ctx, false);
            return LoopToolRoundEngine.DeferredReplayOutcome.failed(toolCalls,
                    tally.firstFatalCode, tally.joinedDetail(), tally.firstFatalRetryable);
        }
        if (tally.fatalCount > 0 && tally.successCount > 0) {
            Events.businessError(bus, tally.firstFatalCode, tally.joinedDetail(),
                    tally.firstFatalRetryable);
        }
        if (!pendingApprovals.isEmpty()) {
            // 重放中再次命中审批：审批挂起语义（含事件发射）整体委托钩子，
            // 剩余调用回存待下次审批决定后继续重放。
            hooks.onApprovalBarrier(ctx, pendingApprovals, stillDeferred);
            LoopAttributes.stashDeferredCalls(ctx, stillDeferred);
            LoopAttributes.stashPendingApprovals(ctx, pendingApprovals);
            hooks.onRoundSettled(ctx, false);
            return LoopToolRoundEngine.DeferredReplayOutcome.of(toolCalls,
                    AgentStop.NEEDS_APPROVAL);
        }
        hooks.onRoundSettled(ctx, false);
        return LoopToolRoundEngine.DeferredReplayOutcome.of(toolCalls, null);
    }
}
