// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 技能所属实体类型
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum SkillOwnerType {

    MANAGER("manager", "平台/Butler 管理"),
    AGENT("agent", "Agent 自身管理");

    private final String value;
    private final String description;

    SkillOwnerType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static SkillOwnerType fromValue(String value) {
        if (value == null) return null;
        for (SkillOwnerType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) return type;
        }
        return null;
    }
}
