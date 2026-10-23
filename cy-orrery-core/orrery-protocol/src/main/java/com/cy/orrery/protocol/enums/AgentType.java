// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * Agent 类型枚举
 * <p>
 * 区分不同职责的 Agent，Butler 据此调整路由和编排策略。
 * 纯 Web 服务不在 Agent Catalog 中，仅通过 {@code ServiceInstanceDescriptor}
 * 注册到 Service Discovery。
 * </p>
 *
 * @author pcy
 * @date 2026/03/23
 */
@Getter
public enum AgentType {

    BUTLER("butler", "管家 Agent（负责统一路由与编排）"),
    TOOL("tool", "工具 Agent（提供工具调用能力）"),
    BUSINESS("business", "业务 Agent（处理具体业务场景）");

    private final String value;
    private final String description;

    AgentType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 类型值
     * @return 对应枚举，未找到返回 null
     */
    public static AgentType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AgentType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
