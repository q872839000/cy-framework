package com.cy.auth.filter;

import com.cy.auth.config.AuthProperties;
import com.cy.auth.constants.AuthConstants;
import com.cy.auth.support.AnonymousPathMatcher;
import com.cy.auth.util.GatewaySignatureUtil;
import com.cy.auth.util.TokenValidator;
import com.cy.core.context.AuthUser;
import com.cy.core.context.InvocationContext;
import com.cy.web.context.ReactiveInvocationContextAccessor;
import com.cy.web.exception.ReactiveErrorResponseWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 认证过滤器。
 * <p>
 * 由 {@link com.cy.auth.config.AuthReactiveAutoConfiguration} 在 Reactive 环境下自动注册，
 * 不使用 {@code @Component} 以避免在 Servlet 环境中被扫描到。
 * </p>
 * <p>
 * 认证信息通过 Reactor {@link reactor.util.context.Context} 传播，而非 ThreadLocal。
 * 下游 Handler/Controller 通过 {@link com.cy.web.context.ReactiveInvocationContextAccessor} 获取当前用户。
 * 认证失败消息通过 {@link com.cy.core.exception.MessageResolver} 从 error-messages-auth.properties 解析。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
public class ReactiveAuthWebFilter implements WebFilter, Ordered {

    private final AuthProperties authProperties;
    private final AnonymousPathMatcher anonymousPathMatcher;
    private final ReactiveErrorResponseWriter errorResponseWriter;

    public ReactiveAuthWebFilter(AuthProperties authProperties,
                                  AnonymousPathMatcher anonymousPathMatcher,
                                  ReactiveErrorResponseWriter errorResponseWriter) {
        this.authProperties = authProperties;
        this.anonymousPathMatcher = anonymousPathMatcher;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestUri = request.getPath().pathWithinApplication().value();
        InvocationContext currentContext = currentContext(exchange);

        if (HttpMethod.OPTIONS.equals(request.getMethod())) {
            return chain.filter(exchange);
        }

        if (anonymousPathMatcher.matches(requestUri)) {
            return chain.filter(exchange);
        }

        String token = request.getHeaders().getFirst(AuthConstants.TOKEN_HEADER);
        String gatewayForward = request.getHeaders().getFirst(AuthConstants.GATEWAY_FORWARD_HEADER);
        if (AuthConstants.GATEWAY_FORWARD_VALUE.equals(gatewayForward)) {
            if (!verifyGatewaySignature(request, requestUri)) {
                log.warn("Reactive 网关签名验证失败，疑似伪造请求: uri={}", requestUri);
                return unauthorized(exchange, 40003);
            }
            if (StringUtils.isNotBlank(token)) {
                AuthUser user = TokenValidator.getUserFromToken(token);
                if (user != null) {
                    return authenticateAndContinue(exchange, chain, currentContext, user, true);
                }
            }
            return unauthorized(exchange, 40004);
        }

        if (StringUtils.isBlank(token)) {
            return unauthorized(exchange, 40001);
        }

        // 完整鉴权入口会携带配置参数，校验成功后由 TokenValidator 按需执行滑动续期
        AuthUser user = TokenValidator.validateToken(token,
                authProperties.getEffectiveAccessTokenExpiresIn(),
                authProperties.getEffectiveAccessTokenRenewThresholdIn(),
                authProperties.isRenewTokenOnAccess());
        if (user == null) {
            return unauthorized(exchange, 40002);
        }

        return authenticateAndContinue(exchange, chain, currentContext, user, false);
    }

    /**
     * 认证过滤器优先级。
     * <p>
     * 必须晚于基础设施层过滤器（CORS、Tracing、Metrics 等）执行。
     * {@code OrderedCorsWebFilter} 的 order 为 {@code Ordered.HIGHEST_PRECEDENCE}，
     * 认证设为 0 确保在 CORS 响应头写入之后执行。
     * </p>
     */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 认证上下文传播。
     * <p>
     * 将已认证的 {@link InvocationContext} 写入两个载体：
     * <ul>
     *   <li>{@link ServerWebExchange} 属性 — 供 WebHandler / AOP 等组件通过属性读取</li>
     *   <li>Reactor {@code Context} — 供响应式链路中通过
     *       {@link com.cy.web.context.ReactiveInvocationContextAccessor} 读取</li>
     * </ul>
     * </p>
     */
    private Mono<Void> authenticateAndContinue(ServerWebExchange exchange,
                                               WebFilterChain chain,
                                               InvocationContext currentContext,
                                               AuthUser user,
                                               boolean gatewayForwarded) {
        InvocationContext authenticatedContext = currentContext.withAuthenticatedUser(user, gatewayForwarded);
        ReactiveInvocationContextAccessor.bind(exchange, authenticatedContext);
        return chain.filter(exchange)
                .contextWrite(ReactiveInvocationContextAccessor.contextWriter(authenticatedContext));
    }

    private InvocationContext currentContext(ServerWebExchange exchange) {
        return ReactiveInvocationContextAccessor.getOrCreate(exchange);
    }

    private boolean verifyGatewaySignature(ServerHttpRequest request, String requestUri) {
        String secret = authProperties.getGatewaySecret();
        if (StringUtils.isBlank(secret)) {
            if (authProperties.isRequireGatewaySignature()) {
                log.error("cy.auth.gateway-secret 未配置，拒绝受网关签名保护的转发请求: uri={}", requestUri);
                return false;
            }
            log.warn("cy.auth.gateway-secret 未配置，已按配置关闭网关签名强校验: uri={}", requestUri);
            return true;
        }

        String signature = request.getHeaders().getFirst(AuthConstants.GATEWAY_SIGNATURE_HEADER);
        String timestampStr = request.getHeaders().getFirst(AuthConstants.GATEWAY_TIMESTAMP_HEADER);
        if (StringUtils.isAnyBlank(signature, timestampStr)) {
            return false;
        }

        try {
            long timestamp = Long.parseLong(timestampStr);
            return GatewaySignatureUtil.verify(secret, requestUri, timestamp, signature, AuthConstants.SIGNATURE_TOLERANCE_MS);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 构建 401 未授权响应，消息从 error-messages-auth.properties 解析，
     * 输出格式对齐 {@link com.cy.core.base.R}。
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, int errorCode) {
        return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, errorCode);
    }
}
