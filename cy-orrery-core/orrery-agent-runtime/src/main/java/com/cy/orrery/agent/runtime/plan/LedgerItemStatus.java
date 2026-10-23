// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;

/**
 * 账本条目状态。
 *
 * @author pcy
 * @since 2026/08/19
 */
public enum LedgerItemStatus {

    /** 待办。 */
    PENDING,

    /** 进行中（已有部分匹配的动作证据）。 */
    IN_PROGRESS,

    /** 已完成（终态）。 */
    DONE,

    /** 已失败（终态）。 */
    FAILED,

    /** 已放弃（终态；重排或用户改需求时废弃）。 */
    DROPPED;

    /**
     * 是否终态（终态条目不再参与勾稽推进）。
     *
     * @return true 表示终态
     */
    public boolean terminal() {
        return this == DONE || this == FAILED || this == DROPPED;
    }
}
