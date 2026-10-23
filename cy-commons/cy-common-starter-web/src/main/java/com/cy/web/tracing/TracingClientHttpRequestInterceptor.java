package com.cy.web.tracing;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * RestTemplate 拦截器：自动注入 X-Trace-Id 和 X-Span-Id 请求头。
 * <p>
 * 确保服务间 HTTP 同步调用时链路追踪 ID 不丢失。
 * 在普通请求上下文中，发送的是当前服务入口 span 的 spanId；
 * 若当前线程处于 {@code @AccessLog} 方法作用域内，则会自动发送当前活动方法 span 的 spanId。
 *
 * @author pcy
 * @date 2026/04/09
 */
public class TracingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        InvocationContext context = InvocationContextAccessor.current();
        if (context != null) {
            if (context.getTraceId() != null) {
                request.getHeaders().putIfAbsent(InvocationContext.TRACE_HEADER,
                        java.util.List.of(context.getTraceId()));
            }
            // 始终发送当前调用作用域的 spanId，下游会将其作为 parentSpanId。
            if (context.getSpanId() != null) {
                request.getHeaders().set(InvocationContext.SPAN_HEADER,
                        context.getSpanId());
            }
        }
        return execution.execute(request, body);
    }
}
