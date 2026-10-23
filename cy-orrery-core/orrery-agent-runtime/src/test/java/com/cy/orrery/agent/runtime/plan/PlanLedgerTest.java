// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务账本单元测试（勾稽推进 / 无推进 / 全完成判定 / 属性携带）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class PlanLedgerTest {

    /** 包含式匹配器：动作叙述包含条目描述即完成（测试用简单语义）。 */
    private static final LedgerProgressMatcher CONTAINS_MATCHER = (item, action) ->
            action != null && action.contains(item.description())
                    ? LedgerProgressMatcher.MatchResult.COMPLETED
                    : LedgerProgressMatcher.MatchResult.NO_MATCH;

    /**
     * 勾稽推进：动作匹配的条目完成，revision 递增。
     */
    @Test
    void shouldAdvanceMatchedItems() {
        PlanLedger ledger = PlanLedger.of(List.of(
                PlanLedger.LedgerItem.pending("L1", "部署红方飞机"),
                PlanLedger.LedgerItem.pending("L2", "部署蓝方舰船")));

        Optional<PlanLedger> updated = LedgerReconciler.reconcile(
                ledger, List.of("已完成：部署红方飞机 3 架"), CONTAINS_MATCHER);

        assertTrue(updated.isPresent());
        assertEquals(1, updated.get().revision());
        assertEquals(LedgerItemStatus.DONE, updated.get().items().get(0).status());
        assertEquals(LedgerItemStatus.PENDING, updated.get().items().get(1).status());
        assertEquals(1, updated.get().doneCount());
        assertEquals(1, updated.get().remainingCount());
    }

    /**
     * 无推进：不产生新实例（引用同一）。
     */
    @Test
    void shouldReturnEmptyWhenNoProgress() {
        PlanLedger ledger = PlanLedger.of(List.of(
                PlanLedger.LedgerItem.pending("L1", "部署红方飞机")));

        Optional<PlanLedger> updated = LedgerReconciler.reconcile(
                ledger, List.of("无关动作"), CONTAINS_MATCHER);

        assertTrue(updated.isEmpty());
        assertFalse(ledger.allSettled());
    }

    /**
     * IN_PROGRESS 推进：PENDING 条目收到部分进展动作后进入进行中，再收到完成动作后完成。
     */
    @Test
    void shouldAdvanceThroughInProgress() {
        LedgerProgressMatcher matcher = (item, action) -> {
            if (action == null) {
                return LedgerProgressMatcher.MatchResult.NO_MATCH;
            }
            if (action.contains(item.description() + " 完成")) {
                return LedgerProgressMatcher.MatchResult.COMPLETED;
            }
            if (action.contains(item.description())) {
                return LedgerProgressMatcher.MatchResult.IN_PROGRESS;
            }
            return LedgerProgressMatcher.MatchResult.NO_MATCH;
        };
        PlanLedger ledger = PlanLedger.of(List.of(
                PlanLedger.LedgerItem.pending("L1", "部署")));

        PlanLedger inProgress = LedgerReconciler.reconcile(
                ledger, List.of("部署 开始"), matcher).orElseThrow();
        assertEquals(LedgerItemStatus.IN_PROGRESS, inProgress.items().get(0).status());

        PlanLedger done = LedgerReconciler.reconcile(
                inProgress, List.of("部署 完成"), matcher).orElseThrow();
        assertEquals(LedgerItemStatus.DONE, done.items().get(0).status());
        assertTrue(done.allSettled());
        assertEquals(2, done.revision());
    }

    /**
     * 终态条目不参与推进（FAILED 条目不被覆盖回 DONE）。
     */
    @Test
    void shouldSkipTerminalItems() {
        PlanLedger ledger = new PlanLedger(List.of(
                new PlanLedger.LedgerItem("L1", "部署", LedgerItemStatus.FAILED, null)), 0);

        Optional<PlanLedger> updated = LedgerReconciler.reconcile(
                ledger, List.of("部署 完成"), CONTAINS_MATCHER);

        assertTrue(updated.isEmpty());
        assertTrue(ledger.allSettled());
        assertSame(ledger.items().get(0), ledger.items().get(0));
    }

    /**
     * 属性携带：stash / ledger / clear 生命周期。
     */
    @Test
    void shouldCarryLedgerInContext() {
        com.cy.orrery.agent.runtime.context.AgentRunContext ctx =
                new com.cy.orrery.agent.runtime.context.AgentRunContext("run-1",
                        new com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus("run-1"),
                        null, null);
        assertNull(PlanLedgerAttributes.ledger(ctx));

        PlanLedger ledger = PlanLedger.of(List.of(
                PlanLedger.LedgerItem.pending("L1", "任务")));
        PlanLedgerAttributes.stash(ctx, ledger);
        assertEquals(ledger, PlanLedgerAttributes.ledger(ctx));

        PlanLedgerAttributes.clear(ctx);
        assertNull(PlanLedgerAttributes.ledger(ctx));
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }
}
