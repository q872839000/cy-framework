// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 账本勾稽器：按本轮新增业务动作推进账本条目（确定性规则，不调 LLM）。
 * <p>
 * 推进规则：任一动作对条目返回 COMPLETED → DONE；返回 IN_PROGRESS 且条目为
 * PENDING → IN_PROGRESS；终态条目不参与。任一条目状态变化即 revision+1；
 * 无变化返回 {@link Optional#empty()}（不产生新实例，事件不重复发射）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class LedgerReconciler {

    private LedgerReconciler() {
    }

    /**
     * 勾稽推进。
     *
     * @param ledger     当前账本
     * @param newActions 本轮新增业务动作叙述（可为空）
     * @param matcher    领域匹配器
     * @return 推进后的新账本；无推进为 empty
     */
    public static Optional<PlanLedger> reconcile(PlanLedger ledger, List<String> newActions,
                                                 LedgerProgressMatcher matcher) {
        if (ledger == null || matcher == null || newActions == null || newActions.isEmpty()) {
            return Optional.empty();
        }
        boolean changed = false;
        List<PlanLedger.LedgerItem> items = new ArrayList<>(ledger.items().size());
        for (PlanLedger.LedgerItem item : ledger.items()) {
            if (item.status().terminal()) {
                items.add(item);
                continue;
            }
            PlanLedger.LedgerItem advanced = advance(item, newActions, matcher);
            changed |= advanced != item;
            items.add(advanced);
        }
        return changed
                ? Optional.of(new PlanLedger(List.copyOf(items), ledger.revision() + 1))
                : Optional.empty();
    }

    /**
     * 单条目推进：任一动作 COMPLETED 即完成；否则最强进展为 IN_PROGRESS。
     *
     * @param item       条目（非终态）
     * @param actions    动作叙述列表
     * @param matcher    领域匹配器
     * @return 推进后的条目；无推进返回原条目（同一实例）
     */
    private static PlanLedger.LedgerItem advance(PlanLedger.LedgerItem item, List<String> actions,
                                                 LedgerProgressMatcher matcher) {
        boolean anyInProgress = false;
        for (String action : actions) {
            LedgerProgressMatcher.MatchResult result = matcher.match(item, action);
            if (result == LedgerProgressMatcher.MatchResult.COMPLETED) {
                return item.withStatus(LedgerItemStatus.DONE);
            }
            if (result == LedgerProgressMatcher.MatchResult.IN_PROGRESS) {
                anyInProgress = true;
            }
        }
        if (anyInProgress && item.status() == LedgerItemStatus.PENDING) {
            return item.withStatus(LedgerItemStatus.IN_PROGRESS);
        }
        return item;
    }
}
