// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.dto;

import com.cy.orrery.protocol.enums.TaskState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent 调用响应
 * <p>
 * Agent 处理调用请求后返回的结果，包含输出数据、执行耗时和使用的能力/技能标识。
 * Butler 据此聚合结果并进行审计记录。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 调用响应")
public class InvokeAgentResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 请求唯一标识（与请求中的 requestId 一致）
     */
    @Schema(description = "请求唯一标识")
    private String requestId;

    /**
     * 执行是否成功
     */
    @Schema(description = "执行是否成功")
    private Boolean success;

    /**
     * 输出数据
     */
    @Schema(description = "输出数据")
    private Map<String, Object> output;

    /**
     * 文本形式的响应内容（对话类 Agent 直接返回文本）
     */
    @Schema(description = "文本形式的响应内容")
    private String content;

    /**
     * 错误码（失败时返回）
     */
    @Schema(description = "错误码")
    private Integer errorCode;

    /**
     * 错误消息（失败时返回）
     */
    @Schema(description = "错误消息")
    private String errorMessage;

    /**
     * 响应来源 Agent ID
     */
    @Schema(description = "响应来源 Agent ID")
    private String agentId;

    /**
     * 实际使用的能力ID
     */
    @Schema(description = "实际使用的能力ID")
    private String capabilityId;

    /**
     * 任务生命周期状态
     */
    @Schema(description = "任务生命周期状态")
    private TaskState taskState;

    /**
     * 执行耗时（毫秒）
     */
    @Schema(description = "执行耗时（毫秒）")
    private Long durationMs;

    /**
     * 扩展属性（宿主自定义提示位；如 Butler 计划确认标记 confirmationKind=plan，
     * 供终态事件发射与前端渲染分流使用）
     */
    @Schema(description = "扩展属性")
    private Map<String, Object> ext;

    /**
     * 实际使用的 LLM 模型名称（如 qwen-plus、gpt-4）
     * <p>
     * Planner 直接回复时从 ChatCompletionResponse 提取；
     * Agent 调用时由 Agent 响应携带。
     * </p>
     */
    @Schema(description = "实际使用的 LLM 模型名称")
    private String model;

    /**
     * 总 Token 消耗数
     * <p>
     * 包含 prompt + completion 的总量。
     * </p>
     */
    @Schema(description = "总 Token 消耗数")
    private Integer totalTokens;

    /**
     * 任务产物列表（文件、图片等工具执行产出的产物）
     * <p>
     * Function Calling 循环中 file_write 等工具产出的文件会自动收集为 Artifact，
     * 附带下载链接和内容（文本文件）。前端可据此渲染下载入口或直接展示内容。
     * </p>
     */
    @Schema(description = "任务产物列表")
    private List<TaskInfo.Artifact> artifacts;
}
