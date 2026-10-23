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
import java.time.Instant;

/**
 * 任务 SSE 事件（对齐 A2A Streaming Events）
 * <p>
 * Streamable HTTP 模式下通过 SSE 推送的事件类型，
 * 客户端据此渲染实时进度、流式文本和最终产物。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务 SSE 事件")
public class TaskEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件类型
     */
    @Schema(description = "事件类型")
    private String type;

    /**
     * 任务ID
     */
    @Schema(description = "任务ID")
    private String taskId;

    /**
     * 上下文ID
     */
    @Schema(description = "上下文ID")
    private String contextId;

    /**
     * 任务状态（状态变更事件时填充）
     */
    @Schema(description = "任务状态")
    private TaskState state;

    /**
     * 文本增量（流式文本事件时填充）
     */
    @Schema(description = "文本增量")
    private String delta;

    /**
     * 产物信息（产物更新事件时填充）
     */
    @Schema(description = "产物信息")
    private TaskInfo.Artifact artifact;

    /**
     * 完整响应（任务完成事件时填充）
     */
    @Schema(description = "完整响应")
    private InvokeAgentResponse response;

    /**
     * 时间戳
     */
    @Schema(description = "时间戳")
    @Builder.Default
    private Instant timestamp = Instant.now();

    // ========== 事件类型常量 ==========

    /** 任务已创建 */
    public static final String TYPE_TASK_CREATED = "task.created";
    /** 任务状态变更 */
    public static final String TYPE_STATUS_UPDATE = "task.status.update";
    /** 流式文本增量 */
    public static final String TYPE_TEXT_DELTA = "task.text.delta";
    /** 产物更新 */
    public static final String TYPE_ARTIFACT_UPDATE = "task.artifact.update";
    /** 任务完成 */
    public static final String TYPE_COMPLETED = "task.completed";
    /** 任务失败 */
    public static final String TYPE_FAILED = "task.failed";

    /**
     * 创建任务已创建事件
     */
    public static TaskEvent created(String taskId, String contextId) {
        return TaskEvent.builder()
                .type(TYPE_TASK_CREATED)
                .taskId(taskId)
                .contextId(contextId)
                .state(TaskState.SUBMITTED)
                .build();
    }

    /**
     * 创建状态变更事件
     */
    public static TaskEvent statusUpdate(String taskId, String contextId, TaskState state) {
        return TaskEvent.builder()
                .type(TYPE_STATUS_UPDATE)
                .taskId(taskId)
                .contextId(contextId)
                .state(state)
                .build();
    }

    /**
     * 创建文本增量事件
     */
    public static TaskEvent textDelta(String taskId, String contextId, String delta) {
        return TaskEvent.builder()
                .type(TYPE_TEXT_DELTA)
                .taskId(taskId)
                .contextId(contextId)
                .delta(delta)
                .build();
    }

    /**
     * 创建任务完成事件
     */
    public static TaskEvent completed(String taskId, String contextId, InvokeAgentResponse response) {
        return TaskEvent.builder()
                .type(TYPE_COMPLETED)
                .taskId(taskId)
                .contextId(contextId)
                .state(TaskState.COMPLETED)
                .response(response)
                .build();
    }

    /**
     * 创建任务失败事件
     */
    public static TaskEvent failed(String taskId, String contextId, String errorMessage) {
        return TaskEvent.builder()
                .type(TYPE_FAILED)
                .taskId(taskId)
                .contextId(contextId)
                .state(TaskState.FAILED)
                .delta(errorMessage)
                .build();
    }

    /**
     * 链式设置 delta 文本（用于在 statusUpdate 事件中附加阶段描述）
     *
     * @param delta 阶段描述文本
     * @return this
     */
    public TaskEvent withDelta(String delta) {
        this.delta = delta;
        return this;
    }
}
