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
 * 韧性描述符
 * <p>
 * 描述 Agent 的容错与重试策略，Butler 据此在调用失败时
 * 决定是否重试、回退或熔断。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "韧性描述符")
public class ResilienceDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 可重试的错误类型列表（如 TIMEOUT、TEMPORARY_UNAVAILABLE）
     */
    @Schema(description = "可重试的错误类型列表")
    private List<String> retryableErrors;

    /**
     * 不可重试的错误类型列表（如 VALIDATION_ERROR、BUSINESS_RULE_VIOLATION）
     */
    @Schema(description = "不可重试的错误类型列表")
    private List<String> nonRetryableErrors;

    /**
     * 降级阈值（连续失败次数达到此值后标记为 DEGRADED）
     */
    @Schema(description = "降级阈值")
    @Builder.Default
    private Integer degradedThreshold = 3;

    /**
     * 不可用阈值（连续失败次数达到此值后标记为 INACTIVE）
     */
    @Schema(description = "不可用阈值")
    @Builder.Default
    private Integer inactiveThreshold = 6;

    /**
     * 单次调用超时毫秒（0 或空 = 使用调用方默认；流式调用按此截断）
     */
    @Schema(description = "单次调用超时毫秒（0 或空使用默认）")
    private Long timeoutMs;

    /**
     * 该 Agent 的最大并发调用数（空 = 不限制；Butler 侧按 agentId 令牌桶执行）
     */
    @Schema(description = "最大并发调用数（空为不限）")
    private Integer maxConcurrency;
}
