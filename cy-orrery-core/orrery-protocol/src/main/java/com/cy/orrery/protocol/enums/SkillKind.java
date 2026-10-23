// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 技能类型
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum SkillKind {

    PROMPT("prompt", "提示词驱动（纯 LLM prompt 编排）"),
    EXECUTABLE("executable", "可执行（包含代码逻辑的技能）"),
    EMBEDDED("embedded", "嵌入式（内嵌于 Agent 的原生能力）");

    private final String value;
    private final String description;

    SkillKind(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static SkillKind fromValue(String value) {
        if (value == null) return null;
        for (SkillKind kind : values()) {
            if (kind.value.equalsIgnoreCase(value.trim())) return kind;
        }
        return null;
    }
}
