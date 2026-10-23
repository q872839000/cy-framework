// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * Agent 运行模式
 * <p>
 * 描述 Agent 的内部实现范式，注册中心和 Butler 据此调整路由和编排策略。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum AgentMode {

    WORKFLOW("workflow", "工作流驱动（内部流程编排为主）"),
    SKILL_DRIVEN("skill-driven", "技能驱动（以 Skill Bundle 为核心能力单元）");

    private final String value;
    private final String description;

    AgentMode(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static AgentMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (AgentMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return null;
    }
}
