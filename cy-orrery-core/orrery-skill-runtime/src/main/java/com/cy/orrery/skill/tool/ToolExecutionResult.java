// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具执行结果
 * <p>
 * 封装内置工具执行后的输出，包含成功/失败状态、输出数据、
 * 错误信息、执行耗时等。与 {@link com.cy.orrery.skill.executor.SkillExecutionResult}
 * 结构对齐，便于统一处理。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResult {

    /**
     * 工具ID
     */
    private String toolId;

    /**
     * 是否执行成功
     */
    private boolean success;

    /**
     * 输出数据
     */
    private Map<String, Object> output;

    /**
     * 文本输出（工具主输出内容，用于 LLM 消费）
     */
    private String textOutput;

    /**
     * 错误码（失败时返回）
     */
    private Integer errorCode;

    /**
     * 错误消息（失败时返回）
     */
    private String errorMessage;

    /**
     * 执行耗时（毫秒）
     */
    private long durationMs;

    /**
     * 构建成功结果
     */
    public static ToolExecutionResult success(String toolId, Map<String, Object> output, long durationMs) {
        return ToolExecutionResult.builder()
                .toolId(toolId)
                .success(true)
                .output(output)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 构建成功结果（含文本输出）
     */
    public static ToolExecutionResult success(String toolId, String textOutput, Map<String, Object> output, long durationMs) {
        return ToolExecutionResult.builder()
                .toolId(toolId)
                .success(true)
                .textOutput(textOutput)
                .output(output)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 构建失败结果
     */
    public static ToolExecutionResult failure(String toolId, int errorCode, String errorMessage) {
        return ToolExecutionResult.builder()
                .toolId(toolId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * 构建失败结果（含耗时）
     */
    public static ToolExecutionResult failure(String toolId, int errorCode, String errorMessage, long durationMs) {
        return ToolExecutionResult.builder()
                .toolId(toolId)
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .durationMs(durationMs)
                .build();
    }
}
