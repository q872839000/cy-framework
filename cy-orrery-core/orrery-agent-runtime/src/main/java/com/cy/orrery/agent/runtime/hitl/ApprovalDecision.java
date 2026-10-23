// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

/**
 * 审批决定。
 *
 * @param approvalId 审批单 ID
 * @param approved   是否批准
 * @param comment    决定备注（可空）
 * @param decidedBy  决定人（可空）
 * @param timedOut   是否超时按拒绝兜底
 * @author pcy
 * @since 2026/08/19
 */
public record ApprovalDecision(
        String approvalId,
        boolean approved,
        String comment,
        String decidedBy,
        boolean timedOut) {

    /**
     * 批准决定工厂。
     *
     * @param approvalId 审批单 ID
     * @param decidedBy  决定人
     * @return 批准决定
     */
    public static ApprovalDecision approve(String approvalId, String decidedBy) {
        return new ApprovalDecision(approvalId, true, null, decidedBy, false);
    }

    /**
     * 拒绝决定工厂。
     *
     * @param approvalId 审批单 ID
     * @param decidedBy  决定人
     * @param comment    拒绝备注（可空）
     * @return 拒绝决定
     */
    public static ApprovalDecision reject(String approvalId, String decidedBy, String comment) {
        return new ApprovalDecision(approvalId, false, comment, decidedBy, false);
    }

    /**
     * 超时兜底决定工厂（按拒绝语义）。
     *
     * @param approvalId 审批单 ID
     * @return 超时决定
     */
    public static ApprovalDecision timedOut(String approvalId) {
        return new ApprovalDecision(approvalId, false, "审批等待超时", "system", true);
    }

    /**
     * 中止兜底决定工厂（运行取消释放等待者时按拒绝语义）。
     *
     * @param approvalId 审批单 ID
     * @return 中止决定
     */
    public static ApprovalDecision aborted(String approvalId) {
        return new ApprovalDecision(approvalId, false, "审批被中止", "system", false);
    }
}
