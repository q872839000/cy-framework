// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.Events;
import java.util.List;
import java.util.Map;

/**
 * 工具轮领域钩子。
 * <p>
 * 工具轮引擎托管的编排（审批屏障 / 暂缓重放 / 部分失败结算）中，
 * 需要领域表达的部分经本接口注入：审批挂起发射权、业务动作收集、
 * 轮结算 flush、挂起与升级文案。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface ToolRoundHooks {

    /**
     * 成功工具调用的业务动作收集（宿主把用户可读叙述追加进动作日志）。
     *
     * @param result    工具执行结果
     * @param arguments 已解析参数
     * @param actionLog 业务动作日志（可变，直接追加）
     */
    default void onToolCallSucceeded(ToolCallResult result, Map<String, Object> arguments,
                                     List<String> actionLog) {
    }

    /**
     * 审批屏障命中：宿主接管点。
     * <p>
     * 默认实现发 runtime 标准审批事件与挂起提示（独立使用 runtime 时可用）；
     * 宿主自带审批协调器时覆写本钩子——注册审批门、发宿主 wire 审批事件、
     * 组装挂起态契约等全部由宿主承担，引擎不再发任何审批相关事件
     * （消除双发，事件发射权单一）。
     * </p>
     *
     * @param ctx      运行上下文
     * @param pending  待审批请求列表（至少一项）
     * @param deferred 被屏障暂缓的后续调用（可为空）
     */
    default void onApprovalBarrier(AgentRunContext ctx, List<ApprovalRequest> pending,
                                   List<DeferredToolCall> deferred) {
        for (ApprovalRequest approval : pending) {
            Events.approvalRequired(ctx.getBus(), approval.approvalId(), approval.summary(),
                    approval.callId(), approval.affectedKeys());
        }
        Events.systemTrace(ctx.getBus(), "approval.suspend", approvalSuspendTip(!deferred.isEmpty()));
    }

    /**
     * 工具轮结算钩子（主路径与重放路径收尾各调一次，含挂起路径）。
     * <p>
     * 宿主在此 flush 领域增量、暂存领域携带状态（引擎自带的 actionLog / deferred
     * 已随上下文属性自动携带，无需在此处理）。
     * </p>
     *
     * @param ctx            运行上下文
     * @param partialFailure 本轮是否存在部分失败
     */
    default void onRoundSettled(AgentRunContext ctx, boolean partialFailure) {
    }

    /**
     * 审批挂起时面向用户的提示文案。
     *
     * @param hasDeferred 是否有被暂缓的后续调用
     * @return 提示文案
     */
    default String approvalSuspendTip(boolean hasDeferred) {
        return hasDeferred
                ? "前序操作需人工审批，后续步骤已暂缓；审批通过后将自动继续执行。"
                : "本次操作需人工审批，审批通过后将自动继续执行。";
    }

    /**
     * 审批屏障暂缓调用的占位回执 JSON（维持 assistant.tool_calls 与 tool 消息配对协议）。
     *
     * @return 占位回执 JSON
     */
    default String deferredPlaceholderReceipt() {
        return "{\"deferred\":true,\"message\":\"前序操作需人工审批，本步骤已暂缓；审批决定后由系统自动继续执行。\"}";
    }

    /**
     * 审批批准后的合成回执 JSON（覆盖触发审批的调用的占位回执）。
     *
     * @param approval 审批请求
     * @return 批准回执 JSON
     */
    default String approvalApprovedReceipt(ApprovalRequest approval) {
        return "{\"approved\":true,\"message\":\"操作已获人工批准，继续执行。\"}";
    }

    /**
     * 审批拒绝（或超时按拒绝兜底）后的合成回执 JSON。
     *
     * @param approval 审批请求
     * @param timedOut 是否超时兜底
     * @return 拒绝回执 JSON
     */
    default String approvalRejectedReceipt(ApprovalRequest approval, boolean timedOut) {
        return "{\"approved\":false,\"message\":\""
                + (timedOut ? "审批等待超时，已按拒绝处理。该操作未执行。"
                            : "该操作已被人工拒绝，未执行。请勿原样重试。")
                + "\"}";
    }

    /**
     * 单工具连续失败升级文案（达到阈值时注入失败回执，引导 LLM 换路）。
     *
     * @param toolName 工具名
     * @param count    连续失败次数
     * @return 升级提示文案
     */
    default String toolFailureEscalation(String toolName, int count) {
        return "工具 " + toolName + " 已连续失败 " + count
                + " 次。请检查并调整本次调用的参数后重试，或换用其它工具达成目标；不要原样重复同一调用。";
    }

    /**
     * 空实现单例（无领域动作收集语义时使用）。
     */
    ToolRoundHooks NO_OP = new ToolRoundHooks() {
    };
}
