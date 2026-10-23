// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;

/**
 * 账本勾稽匹配 SPI：判定「一条业务动作叙述是否推进了某个账本条目」。
 * <p>
 * 纯领域语义注入点（如按工具名 + 参数做精确匹配）；引擎不提供默认实现
 * （文本模糊匹配太脆弱，宁缺毋滥）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface LedgerProgressMatcher {

    /**
     * 匹配判定。
     *
     * @param item             账本条目（非终态）
     * @param actionNarration  业务动作叙述（本轮成功工具动作的用户可读文本）
     * @return 匹配结果
     */
    MatchResult match(PlanLedger.LedgerItem item, String actionNarration);

    /** 匹配结果。 */
    enum MatchResult {

        /** 该动作完成了此条目（推进到 DONE）。 */
        COMPLETED,

        /** 该动作属于此条目的部分进展（PENDING 推进到 IN_PROGRESS）。 */
        IN_PROGRESS,

        /** 无关（不推进）。 */
        NO_MATCH
    }
}
