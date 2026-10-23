package com.cy.auth.config;

import com.cy.auth.constants.AuthConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证配置属性。
 * <p>
 * 匿名路径（免鉴权）的完整优先级：
 * <ol>
 *   <li>内置默认规则（静态资源、swagger 文档等）— {@code DefaultAnonymousPathPatterns}</li>
 *   <li>{@code @AnonymousAccess} 注解标记的 Controller 方法/类</li>
 *   <li>{@code AnonymousPathContributor} SPI（编程式扩展，如网关转发路径）</li>
 *   <li>本配置中的 {@code whitelist}（YAML 运维配置，无需改代码即可放行）</li>
 * </ol>
 * 四层来源在启动时合并，运行时统一匹配。
 * </p>
 *
 * @author pcy
 * @date 2026/1/12
 */
@Data
@ConfigurationProperties(prefix = "cy.auth")
public class AuthProperties {

    /**
     * 额外免鉴权路径白名单（支持 Ant 风格匹配）。
     * <p>
     * 用于运维场景下不改代码即可放行特定路径，与 {@code @AnonymousAccess} 注解、
     * {@code DefaultAnonymousPathPatterns} 内置规则、{@code AnonymousPathContributor} SPI 合并生效。
     * </p>
     * 配置示例：
     * <pre>
     * cy:
     *   auth:
     *     whitelist:
     *       - /custom/public-api/**
     *       - /health
     * </pre>
     */
    private List<String> whitelist = new ArrayList<>();

    /**
     * 访问令牌有效期，单位：秒。
     */
    private long accessTokenExpiresIn = AuthConstants.DEFAULT_ACCESS_TOKEN_EXPIRES_IN;

    /**
     * 访问令牌滑动续期阈值，单位：秒。
     * <p>
     * 只有请求完成完整 Token 校验，并且 Redis 中 token 剩余 TTL 小于等于该阈值时，才会续期；
     * 未配置、配置小于等于 0 或配置大于等于有效期时，默认使用有效期的一半，避免每次访问都写 Redis。
     * </p>
     */
    private long accessTokenRenewThresholdIn = 0L;

    /**
     * 是否启用访问触发的 Token 滑动续期。
     */
    private boolean renewTokenOnAccess = true;

    /**
     * 网关签名共享密钥（网关和下游服务必须配置相同的值）。
     * 用于 HMAC-SHA256 签名验证，防止外部伪造 X-Gateway-Forward 头。
     * 配置示例：
     * <pre>
     * cy:
     *   auth:
     *     gateway-secret: your-shared-secret-key
     * </pre>
     */
    private String gatewaySecret;

    /**
     * 是否要求网关转发请求必须校验签名。
     * 默认开启，避免未配置 shared secret 时静默放行伪造请求。
     */
    private boolean requireGatewaySignature = true;

    /**
     * 获取最终生效的访问令牌有效期。
     * <p>
     * 配置值非法时回退到默认值，避免写入无效 Redis TTL。
     * </p>
     */
    public long getEffectiveAccessTokenExpiresIn() {
        if (accessTokenExpiresIn <= 0) {
            return AuthConstants.DEFAULT_ACCESS_TOKEN_EXPIRES_IN;
        }
        return accessTokenExpiresIn;
    }

    /**
     * 获取最终生效的滑动续期阈值。
     * <p>
     * 阈值未配置或不合理时，回退为有效期的一半。
     * </p>
     */
    public long getEffectiveAccessTokenRenewThresholdIn() {
        long expiresIn = getEffectiveAccessTokenExpiresIn();
        if (accessTokenRenewThresholdIn <= 0 || accessTokenRenewThresholdIn >= expiresIn) {
            return Math.max(1L, expiresIn / 2);
        }
        return accessTokenRenewThresholdIn;
    }
}
