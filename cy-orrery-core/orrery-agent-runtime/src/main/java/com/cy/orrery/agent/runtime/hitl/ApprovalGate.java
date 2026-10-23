// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

import com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalGate;

/**
 * 审批等待门：挂起后阻塞等待外部决定到达的并发原语。
 * <p>
 * 默认提供进程内实现（{@link InMemoryApprovalGate}）；多副本部署时宿主可替换为
 * 分布式实现（决定投递经广播 / 共享存储路由到持有运行的本实例）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface ApprovalGate {

    /**
     * 注册审批单（挂起时调用，开始追踪）。
     *
     * @param approvalId 审批单 ID
     */
    void register(String approvalId);

    /**
     * 阻塞等待决定。
     *
     * @param approvalId   审批单 ID
     * @param timeoutMillis 超时毫秒；超时返回按拒绝兜底的决定
     * @return 审批决定
     * @throws InterruptedException 等待线程被中断
     */
    ApprovalDecision awaitDecision(String approvalId, long timeoutMillis) throws InterruptedException;

    /**
     * 投递决定（外部审批回调端点经宿主调用）。
     *
     * @param approvalId 审批单 ID
     * @param approved   是否批准
     * @param decidedBy  决定人
     * @param comment    备注（可空）
     * @return true 表示投递成功（审批单在等待中）；false 表示审批单不存在或已决定
     */
    boolean decide(String approvalId, boolean approved, String decidedBy, String comment);

    /**
     * 中止审批单（运行取消时释放等待者，按拒绝语义返回）。
     *
     * @param approvalId 审批单 ID
     */
    void abort(String approvalId);
}
