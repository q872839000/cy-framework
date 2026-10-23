// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool;

import lombok.Getter;

/**
 * 内置工具分类枚举
 * <p>
 * 参考 OpenClaw 工具体系，将内置工具按职责域划分为若干类别。
 * Butler 和 Agent 通过 {@link ToolPolicy} 控制各类别的可用性。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Getter
public enum ToolCategory {

    FILESYSTEM("filesystem", "文件系统操作（读、写、编辑）"),
    EXECUTION("execution", "命令执行与进程管理"),
    MEMORY("memory", "记忆检索与存储"),
    KNOWLEDGE("knowledge", "知识检索与系统目录查询"),
    NETWORK("network", "网络请求与数据抓取"),
    SESSION("session", "会话与子 Agent 编排"),
    UTILITY("utility", "通用辅助工具");

    private final String value;
    private final String description;

    ToolCategory(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 分类值
     * @return 对应枚举，未找到返回 null
     */
    public static ToolCategory fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (ToolCategory category : values()) {
            if (category.value.equalsIgnoreCase(value.trim())) {
                return category;
            }
        }
        return null;
    }
}
