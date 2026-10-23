// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;

import java.util.List;

/**
 * 任务账本：显式计划的数据载体（Plan-and-Execute 的计划半边）。
 * <p>
 * 与隐式规划（ReAct 每轮自主决策）互补：宿主在运行前产出初始账本（如一次
 * 结构化 LLM 调用），引擎每轮工具执行后按业务动作日志勾稽推进；全部条目
 * 到达终态且领域允许时可提前收敛（省轮次即省 token）。数据结构不可变，
 * 推进由 {@link LedgerReconciler} 产出新实例（revision 递增）。
 * </p>
 *
 * @param items    条目列表（保持构造顺序）
 * @param revision 修订号（每次勾稽推进 +1，进度事件的观测锚点）
 * @author pcy
 * @since 2026/08/19
 */
public record PlanLedger(List<LedgerItem> items, int revision) {

    /**
     * 初始账本工厂（全部条目按给定状态进入，revision=0）。
     *
     * @param items 条目列表
     * @return 账本
     */
    public static PlanLedger of(List<LedgerItem> items) {
        return new PlanLedger(List.copyOf(items), 0);
    }

    /**
     * 是否全部条目到达终态（DONE / FAILED / DROPPED）。
     *
     * @return true 表示全部终态
     */
    public boolean allSettled() {
        return items.stream().allMatch(item -> item.status().terminal());
    }

    /**
     * 未完成条目数（非终态）。
     *
     * @return 未完成数
     */
    public int remainingCount() {
        return (int) items.stream().filter(item -> !item.status().terminal()).count();
    }

    /**
     * 已完成条目数。
     *
     * @return 已完成数
     */
    public int doneCount() {
        return (int) items.stream().filter(item -> item.status() == LedgerItemStatus.DONE).count();
    }

    /**
     * 进度摘要（如「计划进度 2/5」），供事件 data 与终局文案使用。
     *
     * @return 进度摘要
     */
    public String progressSummary() {
        return "计划进度 " + doneCount() + "/" + items.size();
    }

    /**
     * 账本条目。
     *
     * @param id          条目 ID（如 L1、L2；勾稽与事件引用的稳定键）
     * @param description 条目描述（领域可读的任务说明）
     * @param status      条目状态
     * @param activeForm  执行态文案（进行中展示；可空，空时回退 description）
     */
    public record LedgerItem(String id, String description, LedgerItemStatus status, String activeForm) {

        /**
         * 待办条目工厂（无执行态文案）。
         *
         * @param id          条目 ID
         * @param description 条目描述
         * @return 待办条目
         */
        public static LedgerItem pending(String id, String description) {
            return new LedgerItem(id, description, LedgerItemStatus.PENDING, null);
        }

        /**
         * 待办条目工厂（带执行态文案）。
         *
         * @param id          条目 ID
         * @param description 条目描述
         * @param activeForm  执行态文案
         * @return 待办条目
         */
        public static LedgerItem pending(String id, String description, String activeForm) {
            return new LedgerItem(id, description, LedgerItemStatus.PENDING, activeForm);
        }

        /**
         * 状态迁移副本（原条目不可变）。
         *
         * @param next 新状态
         * @return 新条目
         */
        public LedgerItem withStatus(LedgerItemStatus next) {
            return new LedgerItem(id, description, next, activeForm);
        }

        /**
         * 展示文案（执行态优先，空回退描述）。
         *
         * @return 展示文案
         */
        public String displayText() {
            return activeForm == null || activeForm.isBlank() ? description : activeForm;
        }
    }
}
