// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 记忆数据保留策略
 * <p>
 * 控制 Agent 在会话结束后哪些记忆数据被保留。
 * 注册中心据此校验 Agent 声明的记忆能力一致性。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum RetentionPolicy {

    SESSION_ONLY("session-only", "仅会话记忆（会话结束后全部清除）"),
    SESSION_PLUS_WORKING("session-plus-working", "会话 + 工作记忆"),
    SESSION_PLUS_WORKING_PLUS_LONGTERM("session-plus-working-plus-longterm", "会话 + 工作 + 长期记忆");

    private final String value;
    private final String description;

    RetentionPolicy(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static RetentionPolicy fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (RetentionPolicy policy : values()) {
            if (policy.value.equalsIgnoreCase(value.trim())) {
                return policy;
            }
        }
        return null;
    }
}
