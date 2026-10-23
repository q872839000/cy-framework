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

/**
 * 鉴权描述符
 * <p>
 * 描述 Agent 注册和调用时所需的认证方式，
 * 注册中心用于注册鉴权，Butler/SDK 用于调用鉴权。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "鉴权描述符")
public class AuthDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 认证类型（none / token / signature）
     */
    @Schema(description = "认证类型（none / token / signature）")
    @Builder.Default
    private String authType = "none";

    /**
     * 注册令牌（authType 为 token 时使用）
     */
    @Schema(description = "注册令牌")
    private String token;

    /**
     * 签名密钥标识（authType 为 signature 时使用）
     */
    @Schema(description = "签名密钥标识")
    private String signKeyId;
}
