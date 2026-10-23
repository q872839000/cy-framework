package com.cy.auth.constants;

/**
 * @Author: pcy
 * @Date: 2026/1/12
 * @Description: 认证相关常量
 */
public interface AuthConstants {

    /**
     * 访问令牌默认有效期，单位：秒。
     */
    long DEFAULT_ACCESS_TOKEN_EXPIRES_IN = 7200L;

    /**
     * Token请求头名称
     */
    String TOKEN_HEADER = "Authorization";

    /**
     * 网关转发标识头（存在此头表示请求已经过网关鉴权）
     */
    String GATEWAY_FORWARD_HEADER = "X-Gateway-Forward";

    /**
     * 网关转发标识值
     */
    String GATEWAY_FORWARD_VALUE = "true";

    /**
     * 网关签名头（HMAC-SHA256 签名，防止外部伪造 X-Gateway-Forward）
     */
    String GATEWAY_SIGNATURE_HEADER = "X-Gateway-Signature";

    /**
     * 网关签名时间戳头（毫秒，用于防重放）
     */
    String GATEWAY_TIMESTAMP_HEADER = "X-Gateway-Timestamp";

    /**
     * 签名时间戳容忍窗口（毫秒），超过此窗口的签名视为无效
     */
    long SIGNATURE_TOLERANCE_MS = 60_000L;
}
