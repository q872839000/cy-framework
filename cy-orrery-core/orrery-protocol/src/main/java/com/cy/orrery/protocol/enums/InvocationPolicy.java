// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 技能调用策略
 * <p>
 * 控制谁可以触发技能执行：用户、管家、模型自主或仅内部调用。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum InvocationPolicy {

    USER("user", "由用户显式触发"),
    MANAGER("manager", "由 Butler/管家触发"),
    MODEL("model", "由 LLM 自主决策触发（function-calling）"),
    INTERNAL("internal", "仅内部调用（不对外暴露）");

    private final String value;
    private final String description;

    InvocationPolicy(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static InvocationPolicy fromValue(String value) {
        if (value == null) return null;
        for (InvocationPolicy policy : values()) {
            if (policy.value.equalsIgnoreCase(value.trim())) return policy;
        }
        return null;
    }
}
