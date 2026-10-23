// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.dto;

import com.cy.orrery.protocol.enums.InvokeSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Agent 调用请求
 * <p>
 * Butler 或外部客户端通过此请求调用目标 Agent。
 * 所有跨服务调用均需携带 requestId 以支持幂等和审计追踪。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 调用请求")
public class InvokeAgentRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 请求唯一标识（用于幂等和审计追踪）
     */
    @NotBlank(message = "请求ID不能为空")
    @Schema(description = "请求唯一标识")
    private String requestId;

    /**
     * 目标 Agent ID
     */
    @NotBlank(message = "目标 Agent ID 不能为空")
    @Schema(description = "目标 Agent ID")
    private String agentId;

    /**
     * 目标能力ID（可选，不指定则由 Agent 自行路由）
     */
    @Schema(description = "目标能力ID")
    private String capabilityId;

    /**
     * 用户自然语言消息（对话类 Agent 直接消费此字段）
     */
    @Schema(description = "用户自然语言消息")
    private String message;

    /**
     * 调用输入参数（结构化输入，与 message 互补）
     */
    @Schema(description = "调用输入参数")
    private Map<String, Object> input;

    /**
     * 会话ID（可选，用于多轮对话场景）
     */
    @Schema(description = "会话ID")
    private String sessionId;

    /**
     * 调用超时时间（毫秒，可选，覆盖 Agent 声明的默认超时）
     */
    @Schema(description = "调用超时时间（毫秒）")
    private Long timeoutMs;

    /**
     * 是否要求流式响应
     */
    @Schema(description = "是否要求流式响应")
    @Builder.Default
    private Boolean streaming = false;

    /**
     * 调用来源标识
     */
    @Schema(description = "调用来源标识")
    @Builder.Default
    private InvokeSource source = InvokeSource.BUTLER;

    /**
     * 编排调用深度（防止 Butler→Agent→Butler 无限递归）。
     * 每次 Butler 发起下游调用时 +1，到达上限时拒绝。
     */
    @Schema(description = "编排调用深度")
    @Builder.Default
    private Integer depth = 0;

    /**
     * LLM 提供商覆盖（可选，cy.ai.providers 的 key）
     * <p>
     * 会话级模型偏好由 Butler 转发至此，下游 Agent 的 LLM 调用按名路由；
     * null = 跟随本平台默认 provider。
     * </p>
     */
    @Schema(description = "LLM 提供商覆盖（可选，null = 平台默认）")
    private String llmProvider;

    /**
     * LLM 模型覆盖（可选；配合 {@link #llmProvider} 使用，null = 提供商默认模型）
     */
    @Schema(description = "LLM 模型覆盖（可选，null = 提供商默认模型）")
    private String llmModel;
}
