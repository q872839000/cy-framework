// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 技能来源
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum SkillSource {

    BUNDLED("bundled", "内置捆绑"),
    INSTALLED("installed", "手动安装"),
    CLAWHUB("clawhub", "ClawHub 市场安装"),
    ANTHROPIC("anthropic", "Anthropic Skills"),
    OPENCLAW("openclaw", "OpenClaw Skills");

    private final String value;
    private final String description;

    SkillSource(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static SkillSource fromValue(String value) {
        if (value == null) return null;
        for (SkillSource source : values()) {
            if (source.value.equalsIgnoreCase(value.trim())) return source;
        }
        return null;
    }
}
