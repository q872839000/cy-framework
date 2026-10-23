// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * Skill 安装状态枚举
 * <p>
 * 描述 Skill 在 SkillRuntime 中的生命周期阶段，
 * SkillRegistry 据此判断 Skill 是否可被调度执行。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Getter
public enum SkillInstallState {

    DISCOVERED("discovered", "已发现，尚未安装"),
    INSTALLING("installing", "安装中"),
    INSTALLED("installed", "已安装，可执行"),
    DISABLED("disabled", "已禁用"),
    UNINSTALLED("uninstalled", "已卸载"),
    ERROR("error", "安装或运行异常");

    private final String value;
    private final String description;

    SkillInstallState(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 状态值
     * @return 对应枚举，未找到返回 null
     */
    public static SkillInstallState fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SkillInstallState state : values()) {
            if (state.value.equalsIgnoreCase(value.trim())) {
                return state;
            }
        }
        return null;
    }

    /**
     * 是否可被调度执行
     */
    public boolean isExecutable() {
        return this == INSTALLED;
    }
}
