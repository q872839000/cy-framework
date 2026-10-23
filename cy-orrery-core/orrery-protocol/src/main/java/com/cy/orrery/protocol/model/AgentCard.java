// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Agent Card（对齐 A2A 协议）
 * <p>
 * Agent 对外发布的能力发现文档，遵循 A2A 协议的 Agent Card 规范。
 * 通过 {@code GET /.well-known/agent-card.json} 提供发现入口，
 * 注册中心也可缓存并代理 Agent Card 查询。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A2A Agent Card — Agent 对外发布的能力发现文档")
public class AgentCard implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Agent 名称
     */
    @Schema(description = "Agent 名称")
    private String name;

    /**
     * Agent 描述（面向调用方的简要说明）
     */
    @Schema(description = "Agent 描述")
    private String description;

    /**
     * Agent 版本
     */
    @Schema(description = "Agent 版本")
    private String version;

    /**
     * Agent 图标 URL
     */
    @Schema(description = "Agent 图标 URL")
    private String iconUrl;

    /**
     * Agent 文档 URL
     */
    @Schema(description = "Agent 文档 URL")
    private String documentationUrl;

    /**
     * 提供方信息
     */
    @Schema(description = "提供方信息")
    private Provider provider;

    /**
     * 支持的协议接口列表（按偏好顺序排列）
     */
    @Schema(description = "支持的协议接口列表")
    private List<SupportedInterface> supportedInterfaces;

    /**
     * Agent 能力声明
     */
    @Schema(description = "Agent 能力声明")
    private Capabilities capabilities;

    /**
     * 技能列表（对齐 A2A skills）
     */
    @Schema(description = "技能列表")
    private List<SkillSummary> skills;

    /**
     * 默认支持的输入媒体类型
     */
    @Schema(description = "默认支持的输入媒体类型")
    private List<String> defaultInputModes;

    /**
     * 默认支持的输出媒体类型
     */
    @Schema(description = "默认支持的输出媒体类型")
    private List<String> defaultOutputModes;

    /**
     * 安全方案（简化版，完整版参考 A2A securitySchemes）
     */
    @Schema(description = "鉴权类型（bearer / api-key / none）")
    private String authType;

    // ========== 内嵌对象 ==========

    /**
     * 提供方信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "提供方信息")
    public static class Provider implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "组织名称")
        private String organization;

        @Schema(description = "组织 URL")
        private String url;
    }

    /**
     * 支持的协议接口（对齐 A2A supportedInterfaces）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "支持的协议接口")
    public static class SupportedInterface implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 接口端点 URL
         */
        @Schema(description = "接口端点 URL")
        private String url;

        /**
         * 协议绑定类型（HTTP_JSON / JSON_RPC / GRPC）
         */
        @Schema(description = "协议绑定类型")
        private String protocolBinding;

        /**
         * 协议版本
         */
        @Schema(description = "协议版本")
        @Builder.Default
        private String protocolVersion = "1.0";
    }

    /**
     * Agent 能力声明（对齐 A2A capabilities）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Agent 能力声明")
    public static class Capabilities implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 是否支持流式响应
         */
        @Schema(description = "是否支持流式响应")
        @Builder.Default
        private Boolean streaming = false;

        /**
         * 是否支持推送通知（WebHook）
         */
        @Schema(description = "是否支持推送通知")
        @Builder.Default
        private Boolean pushNotifications = false;

        /**
         * 是否支持任务状态历史
         */
        @Schema(description = "是否支持任务状态历史")
        @Builder.Default
        private Boolean stateTransitionHistory = false;
    }

    /**
     * 技能摘要（对齐 A2A skills[]）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "技能摘要")
    public static class SkillSummary implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 技能ID
         */
        @Schema(description = "技能ID")
        private String id;

        /**
         * 技能名称
         */
        @Schema(description = "技能名称")
        private String name;

        /**
         * 技能描述
         */
        @Schema(description = "技能描述")
        private String description;

        /**
         * 标签
         */
        @Schema(description = "标签")
        private List<String> tags;

        /**
         * 典型用法示例
         */
        @Schema(description = "典型用法示例")
        private List<String> examples;

        /**
         * 支持的输入媒体类型（覆盖 Agent 级默认值）
         */
        @Schema(description = "支持的输入媒体类型")
        private List<String> inputModes;

        /**
         * 支持的输出媒体类型（覆盖 Agent 级默认值）
         */
        @Schema(description = "支持的输出媒体类型")
        private List<String> outputModes;
    }
}
