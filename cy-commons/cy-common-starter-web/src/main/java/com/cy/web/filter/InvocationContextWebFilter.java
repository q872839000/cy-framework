package com.cy.web.filter;

import com.cy.core.context.InvocationContext;
import com.cy.core.utils.ClientIpResolver;
import com.cy.web.context.ReactiveInvocationContextAccessor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * WebFlux 环境的调用上下文初始化过滤器。
 * <p>
 * 在请求处理最早期（认证之前）执行，职责包括：
 * <ol>
 *   <li>从请求头解析或生成 traceId / spanId</li>
 *   <li>创建匿名 {@link InvocationContext} 并写入 {@link ServerWebExchange} 属性和 Reactor Context</li>
 *   <li>将 traceId / spanId 写入响应头，便于客户端关联</li>
 * </ol>
 * 后续认证过滤器（{@code ReactiveAuthWebFilter}）会将匿名上下文升级为已认证上下文。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 * @see InvocationContextFilter Servlet 对应实现
 * @see com.cy.web.context.ReactiveInvocationContextAccessor
 */
public class InvocationContextWebFilter implements WebFilter, Ordered {

    /** 过滤器顺序，需在认证过滤器之前执行 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /**
     * 初始化调用上下文并写入 Reactor Context，贯穿整个响应式链路。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = resolveHeader(request, InvocationContext.TRACE_HEADER);
        // 入站的 X-Span-Id 代表调用方的 Span，存为 parentSpanId。
        String incomingSpanId = request.getHeaders().getFirst(InvocationContext.SPAN_HEADER);
        String parentSpanId = (incomingSpanId != null && !incomingSpanId.isBlank()) ? incomingSpanId : null;
        String spanId = InvocationContext.generateId();
        String remoteAddress = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : null;
        String clientIp = ClientIpResolver.resolve(request.getHeaders()::getFirst, remoteAddress);
        InvocationContext context = InvocationContext.anonymous(
                traceId,
                spanId,
                parentSpanId,
                InvocationContext.SOURCE_EXTERNAL,
                clientIp,
                request.getMethod() != null ? request.getMethod().name() : null,
                request.getPath().pathWithinApplication().value()
        );
        ReactiveInvocationContextAccessor.bind(exchange, context);
        exchange.getResponse().getHeaders().set(InvocationContext.TRACE_HEADER, traceId);
        exchange.getResponse().getHeaders().set(InvocationContext.SPAN_HEADER, spanId);
        return chain.filter(exchange)
                .contextWrite(ReactiveInvocationContextAccessor.contextWriter(context))
                .doFirst(() -> {
                    MDC.put("traceId", traceId);
                    MDC.put("spanId", spanId);
                    if (parentSpanId != null) {
                        MDC.put("parentSpanId", parentSpanId);
                    }
                })
                .doFinally(signal -> {
                    MDC.remove("traceId");
                    MDC.remove("spanId");
                    MDC.remove("parentSpanId");
                });
    }

    /** {@inheritDoc} */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * 从请求头解析指定值，不存在时自动生成 UUID。
     *
     * @param request    响应式 HTTP 请求
     * @param headerName 请求头名称
     * @return 解析到的值或新生成的 UUID
     */
    private String resolveHeader(ServerHttpRequest request, String headerName) {
        return InvocationContext.resolveId(request.getHeaders().getFirst(headerName));
    }
}
