// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl.impl;

import com.cy.orrery.agent.runtime.hitl.ApprovalDecision;
import com.cy.orrery.agent.runtime.hitl.ApprovalDecisionStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内审批决定存储默认实现。
 * <p>
 * 首决定不可覆盖（putIfAbsent）：重复投递 / 竞态双写时先到的决定胜出，
 * 与审批单「一单一决定」语义一致。跨进程恢复需共享存储实现（Redis 等）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/28
 */
public class InMemoryApprovalDecisionStore implements ApprovalDecisionStore {

    private final Map<String, ApprovalDecision> decisions = new ConcurrentHashMap<>();

    @Override
    public void save(ApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision");
        decisions.putIfAbsent(decision.approvalId(), decision);
    }

    @Override
    public Optional<ApprovalDecision> load(String approvalId) {
        return Optional.ofNullable(decisions.get(approvalId));
    }

    /**
     * 当前已落库决定数（可观测性 / 测试用）。
     *
     * @return 决定数
     */
    public int size() {
        return decisions.size();
    }
}
