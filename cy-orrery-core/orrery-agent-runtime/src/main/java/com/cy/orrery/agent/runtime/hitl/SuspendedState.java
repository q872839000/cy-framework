// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;


import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import java.util.List;
import java.util.Map;

/**
 * 挂起态契约：一次挂起的可序列化快照。
 * <p>
 * 宿主的挂起态存储（如 Redis）按本格式存取；存哪里、怎么认领、怎么 fencing
 * 是宿主的事（对应 LangGraph 的 Checkpointer SPI 边界）。引擎侧在审批屏障
 * 命中时组装本契约并存入运行上下文属性，宿主持久化时取出连同自有元数据落库。
 * </p>
 *
 * @param runId             运行 ID
 * @param reason            挂起原因
 * @param pendingApprovals  待审批请求列表（审批类挂起非空）
 * @param awaitingStage     待确认阶段标识（阶段链挂起非空，其余为 null）
 * @param hostExtras        宿主扩展位（如意图快照、业务上下文；不参与引擎语义）
 * @param suspendedAtMillis 挂起时间戳
 * @author pcy
 * @since 2026/08/19
 */
public record SuspendedState(
        String runId,
        SuspensionReason reason,
        List<ApprovalRequest> pendingApprovals,
        String awaitingStage,
        Map<String, Object> hostExtras,
        long suspendedAtMillis) {

    /**
     * 审批挂起快照工厂。
     *
     * @param runId            运行 ID
     * @param pendingApprovals 待审批请求
     * @param hostExtras       宿主扩展位（可空）
     * @return 挂起态
     */
    public static SuspendedState ofApproval(String runId, List<ApprovalRequest> pendingApprovals,
                                            Map<String, Object> hostExtras) {
        return new SuspendedState(runId, SuspensionReason.NEEDS_APPROVAL,
                List.copyOf(pendingApprovals), null, hostExtras, System.currentTimeMillis());
    }

    /**
     * 阶段链挂起快照工厂。
     *
     * @param runId         运行 ID
     * @param awaitingStage 待确认阶段
     * @param hostExtras    宿主扩展位（可空）
     * @return 挂起态
     */
    public static SuspendedState ofStage(String runId, String awaitingStage,
                                         Map<String, Object> hostExtras) {
        return new SuspendedState(runId, SuspensionReason.AWAITING_STAGE,
                List.of(), awaitingStage, hostExtras, System.currentTimeMillis());
    }
}
