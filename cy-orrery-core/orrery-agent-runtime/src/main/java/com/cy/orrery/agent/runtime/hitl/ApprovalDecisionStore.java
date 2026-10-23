// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

import java.util.Optional;

/**
 * 审批决定存储 SPI：决定的持久化账本（决定先落库、再唤醒等待）。
 * <p>
 * 解决「决定先于等待到达即丢弃」：审批回调可能在等待方调
 * {@link ApprovalGate#awaitDecision} 之前（甚至进程重启前）到达——
 * {@code InMemoryApprovalGate.decide} 对未注册 / 已取走的审批单返回 false，
 * 决定若只存在门内就会丢。决定路径必须先 {@link #save} 落库再投递门；
 * 等待路径先 {@link #load} 命中即直接采用（{@code HitlCoordinator.awaitAndApply}
 * 已内置该顺序，宿主经 {@code HitlCoordinator.decide} 投递决定自动满足先落库）。
 * 多副本部署用共享存储实现（Redis 等）；进程内默认实现
 * {@link com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalDecisionStore}。
 * </p>
 *
 * @author pcy
 * @since 2026/08/28
 */
public interface ApprovalDecisionStore {

    /**
     * 保存决定（同审批单重复保存以先到者为准——首决定不可被覆盖）。
     *
     * @param decision 审批决定
     */
    void save(ApprovalDecision decision);

    /**
     * 加载已落库的决定。
     *
     * @param approvalId 审批单 ID
     * @return 已保存的决定；不存在为 empty
     */
    Optional<ApprovalDecision> load(String approvalId);
}
