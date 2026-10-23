package com.cy.core.utils;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.function.Function;

/**
 * 客户端 IP 解析工具。
 *
 * <p>统一封装 Servlet、WebFlux、网关等入口场景下的客户端 IP 解析规则，
 * 仅依赖请求头读取函数和远端地址，不直接耦合具体 Web API。</p>
 */
public final class ClientIpResolver {

    /** 解析失败时返回的占位值。 */
    public static final String UNKNOWN = "unknown";

    /** IPv6 回环地址归一化后的 IPv4 表示。 */
    public static final String LOCALHOST = "127.0.0.1";

    /** 多级代理链头，取首个有效 IP 作为真实客户端地址。 */
    public static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    /** 反向代理直接透传的客户端 IP 头。 */
    public static final String REAL_IP_HEADER = "X-Real-IP";

    private static final List<String> HEADER_NAMES = List.of(
            FORWARDED_FOR_HEADER,
            REAL_IP_HEADER,
            "Proxy-Client-IP",
            "WL-Proxy-Client-IP",
            "HTTP_CLIENT_IP",
            "HTTP_X_FORWARDED_FOR"
    );

    private ClientIpResolver() {
    }

    /**
     * 按统一优先级解析客户端 IP。
     *
     * <p>优先遍历常见代理头中的首个有效 IP；若均不存在或无有效值，
     * 则回退到连接远端地址。</p>
     *
     * @param headerProvider 请求头读取函数，可为 {@code null}
     * @param remoteAddress 连接远端地址，可为 {@code null}
     * @return 解析后的客户端 IP；若无法识别则返回 {@link #UNKNOWN}
     */
    public static String resolve(Function<String, String> headerProvider, String remoteAddress) {
        if (headerProvider != null) {
            for (String headerName : HEADER_NAMES) {
                String candidate = firstValidIp(headerProvider.apply(headerName));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return normalizeIp(remoteAddress);
    }

    /**
     * 归一化单个 IP 字符串。
     *
     * <p>会移除空白、过滤 {@code unknown}，并将 IPv6 回环地址统一映射为
     * {@value #LOCALHOST}。</p>
     *
     * @param ip 原始 IP 字符串
     * @return 归一化后的 IP；若无效则返回 {@link #UNKNOWN}
     */
    public static String normalizeIp(String ip) {
        if (!StringUtils.hasText(ip)) {
            return UNKNOWN;
        }
        String normalized = ip.trim();
        if (!StringUtils.hasText(normalized) || UNKNOWN.equalsIgnoreCase(normalized)) {
            return UNKNOWN;
        }
        if ("0:0:0:0:0:0:0:1".equals(normalized) || "::1".equals(normalized)) {
            return LOCALHOST;
        }
        return normalized;
    }

    /**
     * 从代理头值中提取首个有效 IP。
     *
     * @param rawValue 原始请求头值，可能包含多个逗号分隔 IP
     * @return 首个有效 IP；不存在时返回 {@code null}
     */
    private static String firstValidIp(String rawValue) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        for (String segment : rawValue.split(",")) {
            String candidate = normalizeIp(segment);
            if (!UNKNOWN.equalsIgnoreCase(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
