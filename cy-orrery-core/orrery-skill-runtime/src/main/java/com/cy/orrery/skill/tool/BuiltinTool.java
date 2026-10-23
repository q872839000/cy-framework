// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool;

import com.cy.orrery.protocol.enums.SideEffectLevel;

import java.util.Map;

/**
 * 内置工具接口
 * <p>
 * 定义 Butler 和 Agent 共享的内置工具契约，包含工具身份、分类、参数描述、
 * 安全标记、副作用级别、上下文感知执行等能力。
 * </p>
 * <p>
 * <b>概念分离（对齐 Anthropic Skills / OpenClaw 生态）：</b>
 * Tool 和 Skill 是两个独立概念——Tool 是可执行的函数（由 LLM 通过 function calling 调用），
 * Skill 是提示词模板（SKILL.md 注入到 system prompt 指导 LLM 行为）。
 * 本接口不再继承 {@link com.cy.orrery.skill.spi.Skill}，二者通过各自的 Registry 独立管理。
 * </p>
 * <p>
 * 参考 OpenClaw 的 {@code AnyAgentTool} 模式：统一接口 + 工厂创建 + 策略管控。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public interface BuiltinTool {

    // ==================== 工具身份 ====================

    /**
     * 获取工具唯一标识
     *
     * @return 工具ID（如 "file_read"、"shell_exec"）
     */
    String getSkillId();

    /**
     * 获取工具名称（面向用户和管理界面的显示名）
     *
     * @return 工具名称
     */
    String getName();

    /**
     * 获取工具版本
     *
     * @return 版本号，默认 "1.0.0"
     */
    default String getVersion() {
        return "1.0.0";
    }

    /**
     * 获取工具摘要描述（用于 LLM function calling 的 description 字段）
     *
     * @return 摘要描述
     */
    default String getSummary() {
        return "";
    }

    // ==================== 工具元数据 ====================

    /**
     * 获取工具分类
     *
     * @return 工具分类
     */
    ToolCategory getCategory();

    /**
     * 获取工具参数的 JSON Schema 描述
     * <p>
     * 用于 LLM function-calling 时的参数描述，格式为 JSON Schema 字符串。
     * </p>
     *
     * @return JSON Schema 字符串
     */
    String getParameterSchema();

    /**
     * 是否仅限 owner 调用
     * <p>
     * 参考 OpenClaw 的 ownerOnly 标记，用于限制高权限工具的访问范围。
     * </p>
     *
     * @return true 表示仅 owner 可调用
     */
    default boolean isOwnerOnly() {
        return false;
    }

    /**
     * 获取工具的副作用级别
     * <p>
     * 对齐 MCP 协议的 {@code destructiveHint} / {@code readOnlyHint} / {@code idempotentHint}
     * annotations 和 Orrery 协议层已定义的 {@link SideEffectLevel} 枚举。
     * </p>
     * <ul>
     *   <li>{@code NONE} — 只读操作，无副作用（如 file_read、memory_search、web_fetch）</li>
     *   <li>{@code LOW} — 可逆变更（如 file_write、file_edit）</li>
     *   <li>{@code HIGH} — 不可逆/高危操作，建议用户确认（如 shell_exec、process_manage）</li>
     * </ul>
     *
     * @return 副作用级别，默认 {@link SideEffectLevel#NONE}
     */
    default SideEffectLevel getSideEffectLevel() {
        return SideEffectLevel.NONE;
    }

    // ==================== 工具执行 ====================

    /**
     * 带上下文的工具执行
     * <p>
     * 接收 {@link ToolExecutionContext} 提供工作目录、沙箱约束、调用者身份等运行时信息。
     * </p>
     *
     * @param input   输入参数
     * @param context 执行上下文
     * @return 输出结果
     */
    Map<String, Object> execute(Map<String, Object> input, ToolExecutionContext context);

    /**
     * 无上下文执行（委托到带上下文的方法，使用默认上下文）
     *
     * @param input 输入参数
     * @return 输出结果
     */
    default Map<String, Object> execute(Map<String, Object> input) {
        return execute(input, ToolExecutionContext.builder().build());
    }
}
