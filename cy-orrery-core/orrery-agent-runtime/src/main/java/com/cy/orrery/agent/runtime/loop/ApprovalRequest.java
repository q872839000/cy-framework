// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import java.util.List;

/**
 * 审批请求：工具结果检查器判定「该操作需人工审批」时产出。
 * <p>
 * 引擎命中审批后挂起循环（NEEDS_APPROVAL），同轮后续调用全部暂缓；
 * 审批协调（等待、超时、决定回填）由宿主的 HITL 协调器承担。
 * </p>
 * <p>
 * ⚠️ <b>屏障时机语义</b>：审批判定基于工具<b>已执行</b>的结果——触发审批的
 * 调用在屏障命中前已真实执行，屏障只拦同轮后续调用。被审批工具必须以
 * 「无副作用回执」响应（真实动作在批准后的确定性重放中发生），见
 * {@link ToolCallInspector} 的屏障契约说明。
 * </p>
 *
 * @param approvalId     审批单 ID（宿主审批体系分配）
 * @param summary        审批摘要（面向用户）
 * @param callId         触发审批的工具调用 ID
 * @param affectedKeys   受影响的业务对象键（可空）
 * @author pcy
 * @since 2026/08/19
 */
public record ApprovalRequest(
        String approvalId,
        String summary,
        String callId,
        List<String> affectedKeys) {
}
