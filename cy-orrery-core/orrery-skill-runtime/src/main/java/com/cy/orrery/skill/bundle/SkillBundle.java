// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.bundle;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Skill Bundle 模型（对齐 OpenClaw / ClawHub Skill 包格式）
 * <p>
 * 一个 Skill Bundle 是一个目录，包含：
 * <ul>
 *   <li>{@code SKILL.md} — Skill 元数据（YAML frontmatter + 描述正文）</li>
 *   <li>{@code instructions.md}（可选）— 执行指令</li>
 *   <li>{@code schema.json}（可选）— 输入/输出 JSON Schema</li>
 *   <li>{@code examples/}（可选）— 示例目录</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillBundle {

    /**
     * Skill 唯一标识（从 frontmatter 的 id 字段读取，或目录名）
     */
    private String skillId;

    /**
     * Skill 名称
     */
    private String name;

    /**
     * Skill 版本
     */
    private String version;

    /**
     * Skill 描述（SKILL.md 正文）
     */
    private String description;

    /**
     * 执行指令（instructions.md 内容）
     */
    private String instructions;

    /**
     * 标签
     */
    private List<String> tags;

    /**
     * 作者
     */
    private String author;

    /**
     * 许可证
     */
    private String license;

    /**
     * 输入 JSON Schema（schema.json 中的 input 部分）
     */
    private Map<String, Object> inputSchema;

    /**
     * 输出 JSON Schema（schema.json 中的 output 部分）
     */
    private Map<String, Object> outputSchema;

    /**
     * YAML frontmatter 原始数据
     */
    private Map<String, Object> frontmatter;

    /**
     * Bundle 目录路径
     */
    private String bundlePath;

    /**
     * 示例列表（examples/ 目录下的文件内容）
     */
    private List<String> examples;
}
