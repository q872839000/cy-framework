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
import java.util.List;
import java.util.Map;

/**
 * 任务信息（对齐 A2A Task 模型）
 * <p>
 * A2A 协议的核心操作单元。Butler 为每次 Agent 调用创建一个 Task，
 * 跟踪其从 submitted → working → completed/failed 的完整生命周期。
 * 支持多轮交互（通过 contextId 关联）和异步长任务。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A2A 任务信息")
public class TaskInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 任务唯一标识
     */
    @Schema(description = "任务唯一标识")
    private String taskId;

    /**
     * 上下文标识（多轮会话共享同一 contextId）
     */
    @Schema(description = "上下文标识")
    private String contextId;

    /**
     * 任务状态
     */
    @Schema(description = "任务状态")
    private TaskStatus status;

    /**
     * 任务产物列表
     */
    @Schema(description = "任务产物列表")
    private List<Artifact> artifacts;

    /**
     * 任务消息历史
     */
    @Schema(description = "任务消息历史")
    private List<Message> history;

    /**
     * 扩展元数据
     */
    @Schema(description = "扩展元数据")
    private Map<String, Object> metadata;

    // ========== 内嵌对象 ==========

    /**
     * 任务状态（对齐 A2A TaskStatus）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "任务状态")
    public static class TaskStatus implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 状态枚举
         */
        @Schema(description = "状态枚举")
        private TaskState state;

        /**
         * 状态附加消息（如错误原因、需要的输入提示等）
         */
        @Schema(description = "状态附加消息")
        private Message message;

        /**
         * 状态变更时间戳
         */
        @Schema(description = "状态变更时间戳")
        private Instant timestamp;
    }

    /**
     * 消息（对齐 A2A Message）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "A2A 消息")
    public static class Message implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 消息唯一标识
         */
        @Schema(description = "消息唯一标识")
        private String messageId;

        /**
         * 上下文标识
         */
        @Schema(description = "上下文标识")
        private String contextId;

        /**
         * 关联任务标识
         */
        @Schema(description = "关联任务标识")
        private String taskId;

        /**
         * 角色（user / agent）
         */
        @Schema(description = "角色（user / agent）")
        private String role;

        /**
         * 消息内容片段列表（多模态支持）
         */
        @Schema(description = "消息内容片段列表")
        private List<Part> parts;

        /**
         * 扩展元数据
         */
        @Schema(description = "扩展元数据")
        private Map<String, Object> metadata;
    }

    /**
     * 内容片段（对齐 A2A Part，支持多模态）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "内容片段（多模态）")
    public static class Part implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 文本内容（与 data/url 互斥）
         */
        @Schema(description = "文本内容")
        private String text;

        /**
         * 结构化数据（JSON 对象，与 text/url 互斥）
         */
        @Schema(description = "结构化数据")
        private Object data;

        /**
         * 文件/资源 URL（与 text/data 互斥）
         */
        @Schema(description = "文件/资源 URL")
        private String url;

        /**
         * 媒体类型（如 application/json, text/plain, image/png）
         */
        @Schema(description = "媒体类型")
        private String mediaType;

        /**
         * 文件名（文件类 Part 时使用）
         */
        @Schema(description = "文件名")
        private String filename;
    }

    /**
     * 任务产物（对齐 A2A Artifact）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "任务产物")
    public static class Artifact implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 产物唯一标识
         */
        @Schema(description = "产物唯一标识")
        private String artifactId;

        /**
         * 产物名称
         */
        @Schema(description = "产物名称")
        private String name;

        /**
         * 产物描述
         */
        @Schema(description = "产物描述")
        private String description;

        /**
         * 产物内容片段
         */
        @Schema(description = "产物内容片段")
        private List<Part> parts;

        /**
         * 扩展元数据
         */
        @Schema(description = "扩展元数据")
        private Map<String, Object> metadata;
    }
}
