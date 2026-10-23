// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.prompt;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 提示词技能模型（对齐 Anthropic SKILL.md / OpenClaw / ClawHub 规范）
 * <p>
 * 表示一个从 SKILL.md 文件加载的提示词技能。Skill 不是可执行代码，
 * 而是注入到 LLM system prompt 中的指南，指导 LLM 如何使用已有的 Tool
 * 来完成特定领域的任务。
 * </p>
 * <p>
 * <b>与 Tool 的概念区别：</b>
 * <ul>
 *   <li>Tool（{@link com.cy.orrery.skill.tool.BuiltinTool}）= 可执行函数，
 *       LLM 通过 function calling 调用</li>
 *   <li>Skill（本类）= 提示词模板，注入到 system prompt 指导 LLM 行为</li>
 * </ul>
 * 例如：{@code github} Skill 告诉 LLM "你可以用 gh CLI 操作 GitHub"，
 * 而 {@code shell_exec} Tool 提供执行 gh 命令的能力。二者协同但职责不同。
 * </p>
 * <p>
 * <b>SKILL.md 格式（兼容 Anthropic / OpenClaw / ClawHub）：</b>
 * <pre>
 * ---
 * name: github
 * description: "GitHub operations via gh CLI"
 * metadata:
 *   orrery:
 *     emoji: "🐙"
 *     requires:
 *       bins: ["gh"]
 *     sideEffect: "low"
 * ---
 * # GitHub Skill
 * Use the `gh` CLI to interact with GitHub...
 * (Markdown 正文作为 LLM 指南注入到 system prompt)
 * </pre>
 * </p>
 *
 * @author pcy
 * @date 2026/03/21
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromptSkill {

    /**
     * Skill 名称（从 frontmatter name 字段读取，或目录名）
     * <p>
     * 对应 Anthropic 规范的 {@code name} 字段，用于：
     * <ul>
     *   <li>在 system prompt 中标识 Skill</li>
     *   <li>Skill 去重（同名 Skill 按优先级覆盖）</li>
     *   <li>日志和管理界面显示</li>
     * </ul>
     * </p>
     */
    private String name;

    /**
     * Skill 描述（从 frontmatter description 字段读取）
     * <p>
     * LLM 根据此描述判断何时应该参考这个 Skill 的指南。
     * 对应 Anthropic 规范的 {@code description} 字段。
     * </p>
     */
    private String description;

    /**
     * Skill 提示词正文（SKILL.md 的 Markdown 正文部分，frontmatter 之后的内容）
     * <p>
     * 这是 Skill 的核心内容——注入到 LLM system prompt 中的指南文本。
     * LLM 在处理相关任务时会参考此指南来决定使用哪些 Tool 和采取什么策略。
     * </p>
     */
    private String promptContent;

    /**
     * 来源标识（标识 Skill 从哪个目录/层级加载）
     * <p>
     * 用于 Skill 覆盖优先级和调试。参考 OpenClaw 的 source 字段：
     * <ul>
     *   <li>{@code "bundled"} — 平台内置 Skill</li>
     *   <li>{@code "workspace"} — 工作空间级 Skill（workspace/skills/）</li>
     *   <li>{@code "managed"} — 组织管理级 Skill</li>
     *   <li>{@code "plugin"} — 插件提供的 Skill</li>
     *   <li>{@code "installed"} — 从 ClawHub 等市场安装的 Skill</li>
     * </ul>
     * </p>
     */
    private String source;

    /**
     * SKILL.md 文件的绝对路径
     */
    private String filePath;

    /**
     * Skill 所在目录的绝对路径
     */
    private String baseDir;

    /**
     * 是否禁止 LLM 自动调用此 Skill
     * <p>
     * 对应 Anthropic 规范的 {@code disable-model-invocation} 字段。
     * 为 true 时，此 Skill 的 promptContent 不会被注入到 system prompt，
     * LLM 无法感知也无法自动使用此 Skill。
     * </p>
     */
    @Builder.Default
    private boolean disableModelInvocation = false;

    /**
     * YAML frontmatter 原始数据
     * <p>
     * 保留完整的 frontmatter 供扩展使用（如 metadata.orrery.requires.bins、install 规格等）。
     * </p>
     */
    private Map<String, Object> frontmatter;

    /**
     * Skill 版本（从 frontmatter 读取，可选）
     */
    private String version;

    /**
     * Skill 主页 URL（从 frontmatter homepage 字段读取，可选）
     */
    private String homepage;

    /**
     * OpenClaw/Orrery 元数据（从 frontmatter metadata.orrery 或 metadata.openclaw 读取）
     * <p>
     * 包含 emoji、requires（依赖声明）、install（安装规格）、sideEffect 等扩展字段。
     * </p>
     */
    private Map<String, Object> platformMetadata;

    /**
     * 依赖的外部命令列表（从 metadata.orrery.requires.bins 提取）
     * <p>
     * 用于判断 Skill 是否可用——如果依赖的命令不在系统 PATH 中，Skill 可能无法正常工作。
     * </p>
     */
    private List<String> requiredBins;

    /**
     * 允许使用的工具列表（对齐 Anthropic 规范 {@code allowed-tools} 字段）
     * <p>
     * 限制 Skill 激活后 LLM 可使用的工具范围。例如 {@code ["file_read", "file_write", "shell_exec"]}
     * 表示此 Skill 只能使用文件读写和命令执行工具。
     * 为 null 或空时不限制（可使用所有工具）。
     * </p>
     */
    private List<String> allowedTools;

    /**
     * 指定 Skill 使用的 LLM 模型（对齐 Anthropic 规范 {@code model} 字段）
     * <p>
     * 允许复杂 Skill（如代码审查）指定使用更强的模型。
     * 为 null 时继承当前会话的默认模型。
     * </p>
     */
    private String model;

    /**
     * L3 附属资源（技能目录内除 SKILL.md 外的附属 Markdown 文件：文件名 → 内容）
     * <p>
     * 对应 Anthropic Agent Skills 三层渐进披露结构的第三层：SKILL.md 正文通过
     * 占位符（如 {@code {{include:文件名}}}）按需引用附属文件。加载器只负责
     * 原样收集附属文件内容，<b>占位符如何展开由消费方决定</b>（确定性内联、
     * 按需读取或其他策略），本模型不预设展开语义。
     * </p>
     * <p>
     * 无附属文件时为空 Map（不 consuming 的既有消费方无感知）。
     * </p>
     */
    @Builder.Default
    private Map<String, String> resources = Map.of();

    /**
     * 判断此 Skill 是否应被注入到 LLM system prompt
     * <p>
     * 当 {@code disableModelInvocation=true} 时返回 false，
     * 表示此 Skill 不参与 LLM 的自动调度。
     * </p>
     *
     * @return true 表示应注入到 prompt
     */
    public boolean isEligibleForPromptInjection() {
        return !disableModelInvocation;
    }

    /**
     * 获取提示词正文的字符长度（用于 prompt 总长度限制计算）
     *
     * @return 正文字符数，null 或空时返回 0
     */
    public int getPromptLength() {
        return promptContent != null ? promptContent.length() : 0;
    }
}
