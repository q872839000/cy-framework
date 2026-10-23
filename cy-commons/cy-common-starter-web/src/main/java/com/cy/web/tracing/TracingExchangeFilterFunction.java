package com.cy.web.tracing;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * WebClient 拦截器：自动注入 X-Trace-Id 和 X-Span-Id 请求头。
 * <p>
 * 确保服务间 HTTP 响应式调用时链路追踪 ID 不丢失。
 * <p>
 * 优先从 Reactor Context 读取 {@link InvocationContext}（适用于 WebFlux 环境），
 * 若不存在则回退到 ThreadLocal（适用于 Servlet 环境中使用 WebClient 的场景）。
 * 在普通请求上下文中，发送的是当前服务入口 span 的 spanId；
 * 若当前链路位于 {@code @AccessLog} 作用域内，则会自动发送当前活动方法 span 的 spanId。
 *
 * @author pcy
 * @date 2026/04/09
 */
public class TracingExchangeFilterFunction implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.deferContextual(contextView -> {
            // 优先从 Reactor Context 获取（Reactive 环境）
            InvocationContext context = contextView.hasKey(InvocationContext.class)
                    ? contextView.get(InvocationContext.class)
                    : null;
            // 回退到 ThreadLocal（Servlet 环境使用 WebClient）
            if (context == null) {
                context = InvocationContextAccessor.current();
            }
            if (context != null) {
                ClientRequest.Builder builder = ClientRequest.from(request);
                if (context.getTraceId() != null
                        && !request.headers().containsKey(InvocationContext.TRACE_HEADER)) {
                    builder.header(InvocationContext.TRACE_HEADER, context.getTraceId());
                }
                // 始终发送当前调用作用域的 spanId，下游会将其作为 parentSpanId。
                if (context.getSpanId() != null) {
                    builder.header(InvocationContext.SPAN_HEADER, context.getSpanId());
                }
                return next.exchange(builder.build());
            }
            return next.exchange(request);
        });
    }
}
