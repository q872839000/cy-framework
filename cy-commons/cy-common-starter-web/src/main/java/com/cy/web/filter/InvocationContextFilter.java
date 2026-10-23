package com.cy.web.filter;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationScope;
import com.cy.core.utils.ClientIpResolver;
import com.cy.web.context.ServletInvocationContextAccessor;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * Servlet 环境的调用上下文初始化过滤器。
 * <p>
 * 在请求处理最早期（认证之前）执行，职责包括：
 * <ol>
 *   <li>从请求头解析或生成 traceId / spanId</li>
 *   <li>创建匿名 {@link InvocationContext} 并绑定到当前线程（通过 {@link InvocationScope}）和请求属性</li>
 *   <li>将 traceId / spanId 写入响应头，便于客户端关联</li>
 * </ol>
 * 后续认证拦截器（{@code AuthInterceptor}）会将匿名上下文升级为已认证上下文。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 * @see InvocationContextWebFilter WebFlux 对应实现
 * @see InvocationScope
 */
public class InvocationContextFilter implements Filter, Ordered {

    /** 过滤器顺序，需在认证拦截器之前执行 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    /**
     * 初始化调用上下文并绑定到当前请求生命周期。
     * <p>
     * 使用 try-with-resources 确保请求结束后自动清理 ThreadLocal。
     * </p>
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String traceId = resolveHeader(httpRequest, InvocationContext.TRACE_HEADER);
        // 入站的 X-Span-Id 代表调用方的 Span，存为 parentSpanId。
        String incomingSpanId = httpRequest.getHeader(InvocationContext.SPAN_HEADER);
        String parentSpanId = (incomingSpanId != null && !incomingSpanId.isBlank()) ? incomingSpanId : null;
        // 本服务始终生成新的 spanId 作为自己的入口 Span 标识。
        String spanId = InvocationContext.generateId();
        String clientIp = ClientIpResolver.resolve(httpRequest::getHeader, request.getRemoteAddr());
        InvocationContext context = InvocationContext.anonymous(
                traceId,
                spanId,
                parentSpanId,
                InvocationContext.SOURCE_EXTERNAL,
                clientIp,
                httpRequest.getMethod(),
                httpRequest.getRequestURI()
        );
        ServletInvocationContextAccessor.bind(httpRequest, context);
        httpResponse.setHeader(InvocationContext.TRACE_HEADER, traceId);
        httpResponse.setHeader(InvocationContext.SPAN_HEADER, spanId);
        MDC.put("traceId", traceId);
        MDC.put("spanId", spanId);
        if (parentSpanId != null) {
            MDC.put("parentSpanId", parentSpanId);
        }
        try (InvocationScope ignored = InvocationScope.open(context)) {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            MDC.remove("spanId");
            MDC.remove("parentSpanId");
        }
    }

    /** {@inheritDoc} */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 从请求头解析指定值，不存在时自动生成 UUID。
     *
     * @param request    HTTP 请求
     * @param headerName 请求头名称
     * @return 解析到的值或新生成的 UUID
     */
    private String resolveHeader(HttpServletRequest request, String headerName) {
        return InvocationContext.resolveId(request.getHeader(headerName));
    }
}
