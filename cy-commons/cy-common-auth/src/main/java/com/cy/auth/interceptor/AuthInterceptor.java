package com.cy.auth.interceptor;

import com.cy.auth.config.AuthProperties;
import com.cy.auth.constants.AuthConstants;
import com.cy.auth.support.AnonymousPathMatcher;
import com.cy.auth.util.GatewaySignatureUtil;
import com.cy.auth.util.TokenValidator;
import com.cy.core.context.AuthUser;
import com.cy.core.context.InvocationContext;
import com.cy.web.context.ServletInvocationContextAccessor;
import com.cy.web.exception.ServletErrorResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Servlet 认证拦截器
 * <p>
 * 认证失败消息通过 {@link com.cy.core.exception.MessageResolver} 从 error-messages-auth.properties 解析，
 * 输出格式对齐 {@link com.cy.core.base.R}。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthProperties authProperties;
    private final AnonymousPathMatcher anonymousPathMatcher;
    private final ServletErrorResponseWriter errorResponseWriter;

    public AuthInterceptor(AuthProperties authProperties,
                           AnonymousPathMatcher anonymousPathMatcher,
                           ServletErrorResponseWriter errorResponseWriter) {
        this.authProperties = authProperties;
        this.anonymousPathMatcher = anonymousPathMatcher;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS 预检请求不携带认证信息，直接放行（与 ReactiveAuthWebFilter 对齐）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String requestUri = request.getRequestURI();
        InvocationContext currentContext = currentContext(request);

        if (anonymousPathMatcher.matches(requestUri)) {
            return true;
        }

        // 获取 Token
        String token = request.getHeader(AuthConstants.TOKEN_HEADER);

        // 2. 检查是否为网关转发的请求
        String gatewayForward = request.getHeader(AuthConstants.GATEWAY_FORWARD_HEADER);
        if (AuthConstants.GATEWAY_FORWARD_VALUE.equals(gatewayForward)) {
            // 验证网关签名，防止外部伪造 X-Gateway-Forward 头
            if (!verifyGatewaySignature(request)) {
                log.warn("网关签名验证失败，疑似伪造请求: uri={}", requestUri);
                handleUnauthorized(response, 40003);
                return false;
            }
            // 网关已鉴权，直接从Token获取用户信息（不做完整校验）
            if (StringUtils.isNotBlank(token)) {
                AuthUser user = TokenValidator.getUserFromToken(token);
                if (user != null) {
                    bindAuthenticatedContext(request, currentContext, user, true);
                    return true;
                }
            }
            // 网关转发但无法获取用户信息，返回401
            handleUnauthorized(response, 40004);
            return false;
        }

        // 3. 完整Token校验
        if (StringUtils.isBlank(token)) {
            handleUnauthorized(response, 40001);
            return false;
        }

        // 4. Token校验：完整鉴权入口会携带配置参数，校验成功后由 TokenValidator 按需执行滑动续期
        AuthUser user = TokenValidator.validateToken(token,
                authProperties.getEffectiveAccessTokenExpiresIn(),
                authProperties.getEffectiveAccessTokenRenewThresholdIn(),
                authProperties.isRenewTokenOnAccess());
        if (user == null) {
            handleUnauthorized(response, 40002);
            return false;
        }

        // 5. 存入用户上下文
        bindAuthenticatedContext(request, currentContext, user, false);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
    }

    private InvocationContext currentContext(HttpServletRequest request) {
        return ServletInvocationContextAccessor.getOrCreate(request);
    }

    private void bindAuthenticatedContext(HttpServletRequest request,
                                          InvocationContext currentContext,
                                          AuthUser user,
                                          boolean gatewayForwarded) {
        InvocationContext authenticatedContext = currentContext.withAuthenticatedUser(user, gatewayForwarded);
        ServletInvocationContextAccessor.replace(request, authenticatedContext);
    }

    /**
     * 构建 401 未授权响应，消息从 error-messages-auth.properties 解析，
     * 输出格式对齐 {@link com.cy.core.base.R}。
     */
    private void handleUnauthorized(HttpServletResponse response, int errorCode) throws Exception {
        errorResponseWriter.write(response, HttpStatus.UNAUTHORIZED.value(), errorCode);
    }

    /**
     * 验证网关签名，防止外部伪造 X-Gateway-Forward 头。
     */
    private boolean verifyGatewaySignature(HttpServletRequest request) {
        String secret = authProperties.getGatewaySecret();
        if (StringUtils.isBlank(secret)) {
            if (authProperties.isRequireGatewaySignature()) {
                log.error("cy.auth.gateway-secret 未配置，拒绝受网关签名保护的转发请求: uri={}", request.getRequestURI());
                return false;
            }
            log.warn("cy.auth.gateway-secret 未配置，已按配置关闭网关签名强校验: uri={}", request.getRequestURI());
            return true;
        }

        String signature = request.getHeader(AuthConstants.GATEWAY_SIGNATURE_HEADER);
        String timestampStr = request.getHeader(AuthConstants.GATEWAY_TIMESTAMP_HEADER);
        if (StringUtils.isAnyBlank(signature, timestampStr)) {
            return false;
        }

        try {
            long timestamp = Long.parseLong(timestampStr);
            return GatewaySignatureUtil.verify(
                    secret,
                    request.getRequestURI(),
                    timestamp,
                    signature,
                    AuthConstants.SIGNATURE_TOLERANCE_MS);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
