// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 能力副作用级别
 * <p>
 * 描述 Capability 执行时对外部状态的影响程度，
 * Butler 据此决定是否需要用户确认或增加审计级别。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum SideEffectLevel {

    NONE("none", "无副作用（只读操作）"),
    LOW("low", "低副作用（可逆变更）"),
    HIGH("high", "高副作用（不可逆变更，建议用户确认）");

    private final String value;
    private final String description;

    SideEffectLevel(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static SideEffectLevel fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SideEffectLevel level : values()) {
            if (level.value.equalsIgnoreCase(value.trim())) {
                return level;
            }
        }
        return null;
    }
}
