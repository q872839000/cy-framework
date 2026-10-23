// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * Butler 对 Agent 工作记忆的可见性级别
 * <p>
 * 控制 Butler 能够获取 Agent 工作记忆的程度，
 * 在编排多 Agent 协作时决定上下文传递策略。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum MemoryVisibility {

    FULL("full", "完整可见（Butler 可读取全部工作记忆）"),
    SUMMARY_ONLY("summary-only", "仅摘要可见（Butler 只能读取导出摘要）"),
    NONE("none", "不可见（Butler 无法访问工作记忆）");

    private final String value;
    private final String description;

    MemoryVisibility(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static MemoryVisibility fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MemoryVisibility vis : values()) {
            if (vis.value.equalsIgnoreCase(value.trim())) {
                return vis;
            }
        }
        return null;
    }
}
