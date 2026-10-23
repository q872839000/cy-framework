// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.constant.OrreryConstants;
import com.cy.orrery.protocol.enums.InvocationMode;
import com.cy.orrery.protocol.enums.TransportType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 调用描述符
 * <p>
 * 描述 Agent 支持的调用方式、传输协议、超时策略、重试策略和幂等要求。
 * 注册时由 Agent 提供，Butler 和 SDK 在调用时据此选择对应的传输实现和策略。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "调用描述符")
public class InvocationDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Agent 级调用路径（如 /invoke）
     */
    @Schema(description = "Agent 级调用路径")
    @Builder.Default
    private String invokePath = "/invoke";

    /**
     * 公开能力级调用路径模板（如 /capabilities/{capabilityId}/invoke）
     */
    @Schema(description = "公开能力级调用路径模板")
    private String publicCapabilityInvokePath;

    /**
     * 调用模式
     */
    @Schema(description = "调用模式")
    @Builder.Default
    private InvocationMode mode = InvocationMode.SYNC;

    /**
     * 传输协议
     */
    @Schema(description = "传输协议")
    @Builder.Default
    private TransportType transport = TransportType.HTTP;

    /**
     * 接受的内容类型列表（如 application/json, text/event-stream）
     */
    @Schema(description = "接受的内容类型列表")
    private List<String> acceptContentTypes;

    /**
     * 是否支持流式响应
     */
    @Schema(description = "是否支持流式响应")
    @Builder.Default
    private Boolean streaming = false;

    /**
     * 是否支持异步任务
     */
    @Schema(description = "是否支持异步任务")
    @Builder.Default
    private Boolean supportsAsyncTask = false;

    /**
     * 任务生命周期状态列表（如 submitted, working, input-required, completed, failed, canceled）
     */
    @Schema(description = "任务生命周期状态列表")
    private List<String> taskLifecycle;

    /**
     * 幂等性请求头名称（如 X-Request-Id）
     */
    @Schema(description = "幂等性请求头名称")
    @Builder.Default
    private String idempotencyHeader = "X-Request-Id";

    /**
     * 调用超时时间（毫秒）
     */
    @Schema(description = "调用超时时间（毫秒）")
    @Builder.Default
    private Long timeoutMs = OrreryConstants.DEFAULT_INVOKE_TIMEOUT_MS;

    /**
     * 重试策略
     */
    @Schema(description = "重试策略")
    private RetryPolicy retryPolicy;

    /**
     * 是否要求幂等
     */
    @Schema(description = "是否要求幂等")
    @Builder.Default
    private Boolean idempotencyRequired = false;
}
