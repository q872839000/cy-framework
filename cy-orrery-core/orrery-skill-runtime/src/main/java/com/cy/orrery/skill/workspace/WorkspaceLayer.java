// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.workspace;

import lombok.Getter;

/**
 * Workspace 分层枚举（借鉴 OpenClaw 的 Identity/Soul/User/Agents/Tools 设计）
 * <p>
 * 每个 Agent 可通过 Markdown 文件定义多个 Workspace 层，
 * 运行时按优先级合并为完整的系统指令上下文。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum WorkspaceLayer {

    /**
     * 身份层 — Agent 的基本信息（名称、描述、头像等）
     */
    IDENTITY("IDENTITY.md", 10, "身份层"),

    /**
     * 灵魂层 — Agent 的核心价值观、行为准则、个性、长期目标
     */
    SOUL("SOUL.md", 20, "灵魂层"),

    /**
     * 用户层 — Agent 对特定用户的认知和偏好
     */
    USER("USER.md", 30, "用户层"),

    /**
     * Agent 协作层 — 定义与其他 Agent 的交互规则
     */
    AGENTS("AGENTS.md", 40, "协作层"),

    /**
     * 工具层 — 定义可用工具及使用规则
     */
    TOOLS("TOOLS.md", 50, "工具层");

    private final String filename;
    private final int priority;
    private final String description;

    WorkspaceLayer(String filename, int priority, String description) {
        this.filename = filename;
        this.priority = priority;
        this.description = description;
    }

    /**
     * 根据文件名查找枚举
     *
     * @param filename 文件名
     * @return 对应枚举，未匹配返回 null
     */
    public static WorkspaceLayer fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        for (WorkspaceLayer layer : values()) {
            if (layer.filename.equalsIgnoreCase(filename.trim())) {
                return layer;
            }
        }
        return null;
    }
}
