// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * Skill 可见性枚举
 * <p>
 * 控制 Skill 对外暴露范围。Public Skill 可被 Butler 发现并调用，
 * Private Skill 仅在宿主 Agent 内部使用，Butler 不可越权调用。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Getter
public enum SkillVisibility {

    PUBLIC("public", "公开技能，可被 Butler 发现和调用"),
    PRIVATE("private", "私有技能，仅宿主 Agent 内部使用");

    private final String value;
    private final String description;

    SkillVisibility(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 可见性值
     * @return 对应枚举，未找到返回 null
     */
    public static SkillVisibility fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SkillVisibility v : values()) {
            if (v.value.equalsIgnoreCase(value.trim())) {
                return v;
            }
        }
        return null;
    }
}
