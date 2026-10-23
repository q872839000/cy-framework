// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具轮引擎：ReAct 循环中单轮 tool_calls 的执行编排。
 * <p>
 * ⚠️ <b>审批屏障契约（先执行后判定）</b>：审批 / 澄清检查以工具<b>执行结果</b>为
 * 输入（{@link ToolCallInspector}），触发审批的调用在判定前<b>已真实执行</b>——
 * 屏障拦的是同轮<b>后续</b>调用（暂缓待批），不是触发调用本身。被审批工具必须以
 * 「无副作用回执」响应（预演形态；真实落库动作在批准后的确定性重放中发生），
 * "拒绝"才能等价于"不执行"。
 * </p>
 * <p>
 * 协作分工（各自独立类，本类只做主编排）：
 * <ol>
 *     <li>{@link #executeToolCallsRound} —— 执行本轮 LLM 请求的 tool_calls：审批屏障
 *         （首个审批命中后同轮后续调用全部暂缓并写占位回执）、澄清挂起、
 *         部分失败语义（单调用 fatal 立即中止、多调用跳过、全 fatal 才中止、
 *         部分成功发非终态提示）、参数解析失败回执（不执行工具，模型自纠）；</li>
 *     <li>{@link DeferredReplayer} —— 审批通过后的确定性重放：按原参数逐个执行，
 *         真实回执按 callId 覆盖占位回执，重放中再次命中审批 / 澄清则继续暂缓剩余调用；</li>
 *     <li>{@link ReadOnlyPreExecutor} —— 只读调用的并行预执行（执行并行，挂载保序回引擎线程）；</li>
 *     <li>{@link ToolRoundCodec} —— 参数 JSON 与 Map、结果与 tool 消息 content 的互转。</li>
 * </ol>
 * 事件发射契约：审批挂起事件经 {@link ToolRoundHooks#onApprovalBarrier} 委托
 * （默认实现发标准事件，宿主接管后引擎零审批事件）；澄清 / 业务错误事件归引擎，
 * 经 {@link Events} 工厂发射（data key 契约冻结）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class LoopToolRoundEngine {

    private static final Logger log = LoggerFactory.getLogger(LoopToolRoundEngine.class);

    private final ToolInvoker toolInvoker;
    private final ToolCallInspector inspector;
    private final ToolRoundCodec codec;
    private final ReadOnlyPreExecutor preExecutor;
    private final DeferredReplayer replayer;

    /**
     * 构造工具轮引擎（串行执行）。
     *
     * @param toolInvoker  工具执行 SPI（宿主适配领域工具；建议包 GuardedToolInvoker 获得超时/熔断 harness）
     * @param inspector    审批 / 澄清检查 SPI（可为 {@link ToolCallInspector.NoOp#INSTANCE}）
     * @param objectMapper JSON 序列化器（参数解析）
     */
    public LoopToolRoundEngine(ToolInvoker toolInvoker, ToolCallInspector inspector,
                               ObjectMapper objectMapper) {
        this(toolInvoker, inspector, objectMapper, null);
    }

    /**
     * 构造工具轮引擎（只读调用并行执行）。
     * <p>
     * 传入 {@code readOnlyExecutor} 后启用并行：本轮 {@link ToolInvoker#isReadOnly}
     * 判定为只读且数量 ≥2 的调用先并行预执行（虚拟线程池即可），写调用保持
     * 原串行顺序。语义约束：
     * <ul>
     *     <li>只读调用按 {@code isReadOnly} 契约可在审批屏障前执行（无副作用、幂等，
     *         重放安全）；若宿主给只读工具也配了审批检查，保序挂载阶段屏障命中后，
     *         后续调用（含已预执行的只读）仍按暂缓处理（重放时再执行一次）；</li>
     *     <li>tool 消息挂载顺序严格保持 LLM 请求的原始调用顺序（并行只改变执行
     *         时机，不改变协议配对与上下文形态）；</li>
     *     <li>并行阶段只「执行」，消息挂载 / 审批检查 / 动作收集全部回到引擎线程
     *         保序进行；宿主 ToolInvoker 的只读实现必须线程安全（GuardedToolInvoker
     *         的事件发射与熔断记账基于并发安全组件，可直接用于并行）；</li>
     *     <li>失败连击 / 参数指纹等计数在并行下是近似语义（并发计数竞态），
     *         不影响正确性，仅阈值时机可能差一次。</li>
     * </ul>
     * </p>
     *
     * @param toolInvoker      工具执行 SPI
     * @param inspector        审批 / 澄清检查 SPI（null 用空实现）
     * @param objectMapper     JSON 序列化器
     * @param readOnlyExecutor 只读调用并行执行器（null 表示全串行，与旧行为一致）
     */
    public LoopToolRoundEngine(ToolInvoker toolInvoker, ToolCallInspector inspector,
                               ObjectMapper objectMapper,
                               ExecutorService readOnlyExecutor) {
        this.toolInvoker = toolInvoker;
        this.inspector = inspector == null ? ToolCallInspector.NoOp.INSTANCE : inspector;
        this.codec = new ToolRoundCodec(objectMapper);
        this.preExecutor = new ReadOnlyPreExecutor(toolInvoker, codec, readOnlyExecutor);
        this.replayer = new DeferredReplayer(toolInvoker, inspector, codec);
    }

    /**
     * 单轮工具执行结果（退化熔断观测用）。
     *
     * @param suspend             是否挂起（审批 / 澄清 / 致命失败）
     * @param roundHadToolFailure 本轮是否存在工具失败
     * @param actionLogSizeBefore 本轮开始前业务动作日志大小
     */
    public record ToolRoundResult(boolean suspend, boolean roundHadToolFailure, int actionLogSizeBefore) {
    }

    /**
     * 审批暂缓调用重放的结果。
     *
     * @param toolCallCount   本次重放实际执行数
     * @param stop            NEEDS_APPROVAL 表示重放中再次命中审批；null 表示重放完成
     * @param failedCode      致命失败错误码；非失败路径为 null
     * @param failedMsg       致命失败消息；非失败路径为 null
     * @param failedRetryable 致命失败可重试标志
     */
    public record DeferredReplayOutcome(int toolCallCount, AgentStop stop,
                                        String failedCode, String failedMsg, boolean failedRetryable) {

        /**
         * 非致命路径工厂。
         *
         * @param toolCallCount 执行数
         * @param stop          挂起语义（null 表示完成）
         * @return 重放结果
         */
        public static DeferredReplayOutcome of(int toolCallCount, AgentStop stop) {
            return new DeferredReplayOutcome(toolCallCount, stop, null, null, false);
        }

        /**
         * 致命失败工厂。
         *
         * @param toolCallCount 执行数
         * @param code          错误码
         * @param msg           失败消息
         * @param retryable     可重试标志
         * @return 重放结果
         */
        public static DeferredReplayOutcome failed(int toolCallCount, String code, String msg,
                                                   boolean retryable) {
            return new DeferredReplayOutcome(toolCallCount, AgentStop.BUSINESS_ERROR, code, msg, retryable);
        }
    }

    /** 单轮可变标志（屏障 / 澄清 / 失败 / 部分失败），跨子方法传递避免长参数列表。 */
    private static final class RoundFlags {
        boolean barrierHit;
        boolean clarification;
        boolean toolFailure;
        boolean partialFailure;
    }

    /**
     * 轮次结算输入（轮内收集物聚合载体）。
     *
     * @param pendingApprovals 待审批请求
     * @param deferredCalls    屏障暂缓调用
     * @param flags            轮内可变标志
     * @param actionLogBefore  本轮开始前动作日志大小
     * @param tally            失败统计
     */
    private record RoundSettlement(List<ApprovalRequest> pendingApprovals,
                                   List<DeferredToolCall> deferredCalls, RoundFlags flags,
                                   int actionLogBefore, FailureTally tally) {
    }

    /**
     * 执行本轮 tool_calls（审批屏障 / 澄清挂起 / 部分失败结算）。
     *
     * @param ctx   运行上下文
     * @param acc   循环累加器
     * @param calls 本轮 LLM 请求的 tool_calls
     * @param hooks 领域钩子（审批挂起发射权 / 业务动作收集 / 轮结算）
     * @return 单轮执行结果（suspend=true 表示循环应挂起终止）
     */
    public ToolRoundResult executeToolCallsRound(AgentRunContext ctx, LoopAccumulator acc,
                                                 List<LoopToolCall> calls, ToolRoundHooks hooks) {
        List<String> actionLog = LoopAttributes.actionLog(ctx);
        List<ApprovalRequest> pendingApprovals = new ArrayList<>();
        List<DeferredToolCall> deferredCalls = new ArrayList<>();
        RoundFlags flags = new RoundFlags();
        FailureTally tally = FailureTally.of(calls.size());
        int actionLogBefore = actionLog.size();
        Map<Integer, ReadOnlyPreExecutor.PreExecuted> preExecuted = preExecutor.preExecute(ctx, calls);
        for (int i = 0; i < calls.size(); i++) {
            LoopToolCall call = calls.get(i);
            ReadOnlyPreExecutor.PreExecuted pre = preExecuted.get(i);
            Map<String, Object> args = pre != null ? pre.args() : codec.parseArgs(call.arguments());
            if (args == null) {
                // 参数不是合法 JSON：工具绝不执行（空参会把"解析失败"伪装成"合法无参调用"，
                // 写类工具会造成误执行），失败回执进消息流让模型自纠（可重试语义）。
                settleInvalidArgs(ctx, call, flags);
                continue;
            }
            if (flags.barrierHit) {
                deferCall(ctx, hooks, call, args, deferredCalls);
                continue;
            }
            acc.toolCallCount++;
            if (pre != null) {
                if (pre.fatal() != null) {
                    if (handleFatalCall(ctx, call, tally, pre.fatal())) {
                        return singleFatalSuspend(ctx, acc, hooks, pre.fatal(), actionLogBefore);
                    }
                    flags.toolFailure = true;
                    continue;
                }
                applyCallOutcome(ctx, hooks, pre.result(), args, actionLog, pendingApprovals, tally, flags);
                continue;
            }
            ToolCallResult exec;
            try {
                exec = toolInvoker.invoke(call.id(), call.name(), args, ctx);
            } catch (FatalToolCallException fte) {
                if (handleFatalCall(ctx, call, tally, fte)) {
                    return singleFatalSuspend(ctx, acc, hooks, fte, actionLogBefore);
                }
                flags.toolFailure = true;
                continue;
            }
            applyCallOutcome(ctx, hooks, exec, args, actionLog, pendingApprovals, tally, flags);
        }
        return settleRound(ctx, acc, hooks, new RoundSettlement(pendingApprovals, deferredCalls,
                flags, actionLogBefore, tally));
    }

    /**
     * 重放审批屏障暂缓的兄弟调用（无暂缓时返回 {@code null}）。
     *
     * @param ctx   运行上下文
     * @param acc   循环累加器
     * @param hooks 领域钩子
     * @return 重放结果；无暂缓调用时为 null
     */
    public DeferredReplayOutcome replayDeferredIfNeeded(AgentRunContext ctx, LoopAccumulator acc,
                                                        ToolRoundHooks hooks) {
        return replayer.replayIfNeeded(ctx, acc, hooks);
    }

    // ==================== 主路径子步骤 ====================

    /**
     * 参数解析失败结算：失败回执进消息流（维持 tool_calls 配对协议），标记轮内工具失败
     * （参与退化熔断连击计数）；工具不执行、不计入审批 / 澄清检查。
     *
     * @param ctx   运行上下文
     * @param call  参数非法的调用
     * @param flags 轮内可变标志
     */
    private void settleInvalidArgs(AgentRunContext ctx, LoopToolCall call, RoundFlags flags) {
        RuntimeErrorCode code = RuntimeErrorCode.TOOL_ARGS_INVALID;
        log.warn("工具参数 JSON 解析失败 runId={} tool={} callId={}",
                ctx.getRunId(), call.name(), call.id());
        ToolCallResult failure = ToolCallResult.fail(call.id(), call.name(),
                code.code(), code.format(call.name()), null);
        ctx.addMessage(LoopMessage.tool(call.id(), codec.serialize(failure)));
        flags.toolFailure = true;
    }

    /**
     * 屏障暂缓：把调用按原参数暂存并写占位回执（维持 tool_calls 配对协议）。
     *
     * @param ctx           运行上下文
     * @param hooks         领域钩子（占位回执文案）
     * @param call          被暂缓的调用
     * @param args          已解析参数
     * @param deferredCalls 暂缓调用收集列表（追加）
     */
    private void deferCall(AgentRunContext ctx, ToolRoundHooks hooks, LoopToolCall call,
                           Map<String, Object> args, List<DeferredToolCall> deferredCalls) {
        deferredCalls.add(new DeferredToolCall(call.id(), call.name(), args));
        ctx.addMessage(LoopMessage.tool(call.id(), hooks.deferredPlaceholderReceipt()));
    }

    /**
     * 致命工具错误记账：单调用场景返回 true（调用方应立即中止本轮）。
     *
     * @param ctx   运行上下文
     * @param call  致命失败的调用
     * @param tally 失败统计
     * @param fte   致命工具异常
     * @return true 表示单调用场景应立即中止
     */
    private boolean handleFatalCall(AgentRunContext ctx, LoopToolCall call,
                                    FailureTally tally, FatalToolCallException fte) {
        log.warn("致命工具错误 runId={} tool={} code={}", ctx.getRunId(), call.name(), fte.errorCode());
        tally.recordFatal(fte.errorCode(), fte.getMessage(), fte.retryable());
        ctx.addMessage(LoopMessage.tool(call.id(), fte.getMessage()));
        return tally.totalCalls == 1;
    }

    /**
     * 调用结果落地：成功/失败统计、tool 消息挂载、业务动作收集、审批 / 澄清检查。
     *
     * @param ctx              运行上下文
     * @param hooks            领域钩子（业务动作收集）
     * @param exec             工具执行结果
     * @param args             已解析参数
     * @param actionLog        业务动作日志
     * @param pendingApprovals 待审批请求收集列表（追加）
     * @param tally            失败统计
     * @param flags            轮内可变标志
     */
    private void applyCallOutcome(AgentRunContext ctx, ToolRoundHooks hooks, ToolCallResult exec,
                                  Map<String, Object> args, List<String> actionLog,
                                  List<ApprovalRequest> pendingApprovals,
                                  FailureTally tally, RoundFlags flags) {
        if (exec.success()) {
            tally.successCount++;
        } else {
            flags.toolFailure = true;
        }
        if (exec.partialFailure()) {
            flags.partialFailure = true;
        }
        ctx.addMessage(LoopMessage.tool(exec.callId(), codec.serialize(exec)));
        ApprovalRequest approval = inspector.inspectApproval(exec, ctx);
        if (exec.success() && approval == null) {
            hooks.onToolCallSucceeded(exec, args, actionLog);
        }
        if (approval != null) {
            if (approval.approvalId() != null && !approval.approvalId().isBlank()) {
                pendingApprovals.add(approval);
                flags.barrierHit = true;
            } else {
                // inspector 返回缺 ID 的审批请求属宿主实现 bug：
                // 静默忽略 = 高危操作绕过审批且无线索，必须告警
                log.warn("审批请求缺少 approvalId，已忽略（检查宿主 ToolCallInspector 实现）: "
                        + "runId={} toolCallId={}", ctx.getRunId(), exec.callId());
            }
        }
        ClarificationRequest clarification = inspector.inspectClarification(exec, ctx);
        if (clarification != null) {
            Events.clarification(ctx.getBus(), clarification.question(), clarification.options());
            flags.clarification = true;
            flags.barrierHit = true;
        }
    }

    // ==================== 轮次结算 ====================

    /**
     * 主路径单调用致命失败的立即挂起（唯一的调用即失败，不存在部分失败语义）。
     *
     * @param ctx             运行上下文
     * @param acc             循环累加器
     * @param hooks           领域钩子
     * @param fte             致命工具异常
     * @param actionLogBefore 本轮开始前动作日志大小
     * @return 挂起结果
     */
    private ToolRoundResult singleFatalSuspend(AgentRunContext ctx, LoopAccumulator acc,
                                               ToolRoundHooks hooks,
                                               FatalToolCallException fte, int actionLogBefore) {
        acc.stop = AgentStop.BUSINESS_ERROR;
        acc.errorMsg = fte.getMessage();
        acc.failedCode = fte.errorCode();
        acc.failedRetryable = fte.retryable();
        hooks.onRoundSettled(ctx, false);
        return new ToolRoundResult(true, true, actionLogBefore);
    }

    /**
     * 主路径轮次结算：全 fatal 中止 / 部分失败提示 / 澄清挂起 / 审批挂起 / 正常完成。
     *
     * @param ctx        运行上下文
     * @param acc        循环累加器
     * @param hooks      领域钩子
     * @param settlement 轮内收集物（审批单 / 暂缓调用 / 标志 / 失败统计）
     * @return 单轮执行结果
     */
    private ToolRoundResult settleRound(AgentRunContext ctx, LoopAccumulator acc, ToolRoundHooks hooks,
                                        RoundSettlement settlement) {
        RuntimeEventBus bus = ctx.getBus();
        RoundFlags flags = settlement.flags();
        FailureTally tally = settlement.tally();
        List<ApprovalRequest> pendingApprovals = settlement.pendingApprovals();
        List<DeferredToolCall> deferredCalls = settlement.deferredCalls();
        int actionLogBefore = settlement.actionLogBefore();
        if (tally.allFatalNoSuccess() && !flags.clarification && pendingApprovals.isEmpty()) {
            acc.stop = AgentStop.BUSINESS_ERROR;
            acc.errorMsg = tally.joinedDetail();
            acc.failedCode = tally.firstFatalCode;
            acc.failedRetryable = tally.firstFatalRetryable;
            hooks.onRoundSettled(ctx, flags.partialFailure);
            return new ToolRoundResult(true, true, actionLogBefore);
        }
        if (tally.fatalCount > 0 && tally.successCount > 0) {
            Events.businessError(bus, tally.firstFatalCode, tally.joinedDetail(), tally.firstFatalRetryable);
        }
        if (flags.clarification) {
            // 澄清挂起同样要回存暂缓调用与审批单：澄清命中时 barrier 已立，
            // 后续调用已进 deferredCalls 并收到"决定后自动继续"的占位回执——
            // 不回存 = 恢复后重放读到空列表，被暂缓动作静默丢失（占位回执对 LLM 撒谎）
            LoopAttributes.stashDeferredCalls(ctx, deferredCalls);
            LoopAttributes.stashPendingApprovals(ctx, pendingApprovals);
            acc.stop = AgentStop.NEEDS_CLARIFICATION;
            hooks.onRoundSettled(ctx, flags.partialFailure);
            return new ToolRoundResult(true, flags.toolFailure, actionLogBefore);
        }
        if (!pendingApprovals.isEmpty()) {
            // 审批挂起：事件发射权整体委托钩子（默认实现发标准事件；宿主接管后由宿主协调器发包）。
            hooks.onApprovalBarrier(ctx, pendingApprovals, deferredCalls);
            LoopAttributes.stashDeferredCalls(ctx, deferredCalls);
            LoopAttributes.stashPendingApprovals(ctx, pendingApprovals);
            acc.stop = AgentStop.NEEDS_APPROVAL;
            hooks.onRoundSettled(ctx, flags.partialFailure);
            return new ToolRoundResult(true, flags.toolFailure, actionLogBefore);
        }
        hooks.onRoundSettled(ctx, flags.partialFailure);
        return new ToolRoundResult(false, flags.toolFailure, actionLogBefore);
    }
}
