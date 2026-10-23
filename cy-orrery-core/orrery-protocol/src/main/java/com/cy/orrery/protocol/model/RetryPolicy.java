// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.constant.OrreryConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 重试策略
 * <p>
 * 定义调用失败时的重试行为，包括最大重试次数、重试间隔和可重试的错误码列表。
 * 仅对可重试错误生效，非幂等操作不应启用自动重试。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "重试策略")
public class RetryPolicy implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 最大重试次数
     */
    @Schema(description = "最大重试次数")
    @Builder.Default
    private Integer maxRetries = OrreryConstants.DEFAULT_MAX_RETRIES;

    /**
     * 重试间隔（毫秒）
     */
    @Schema(description = "重试间隔（毫秒）")
    @Builder.Default
    private Long retryIntervalMs = OrreryConstants.DEFAULT_RETRY_INTERVAL_MS;

    /**
     * 是否启用退避策略（指数退避）
     */
    @Schema(description = "是否启用指数退避策略")
    @Builder.Default
    private Boolean exponentialBackoff = true;
}
