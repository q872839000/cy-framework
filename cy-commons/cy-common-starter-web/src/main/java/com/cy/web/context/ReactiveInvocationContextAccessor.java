package com.cy.web.context;

import com.cy.core.context.InvocationContext;
import com.cy.core.utils.ClientIpResolver;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Function;

/**
 * 调用上下文访问器（WebFlux 响应式环境专用）。
 * <p>
 * 在 Reactor 链路中通过 {@link reactor.util.context.ContextView} 读取
 * 由 {@code InvocationContextWebFilter} 写入的 {@link InvocationContext}。
 * </p>
 * <h3>与同步访问器的关系</h3>
 * <ul>
 *   <li>Servlet 环境使用 {@link com.cy.core.context.InvocationContextAccessor}（基于 ThreadLocal）</li>
 *   <li>WebFlux 环境使用本类（基于 Reactor Context）</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/06
 * @see com.cy.core.context.InvocationContextAccessor
 * @see com.cy.web.filter.InvocationContextWebFilter
 */
public final class ReactiveInvocationContextAccessor {

    /** 工具类，禁止实例化 */
    private ReactiveInvocationContextAccessor() {
    }

    /**
     * 从当前 Reactor 上下文中获取 {@link InvocationContext}。
     *
     * @return 包含上下文的 {@link Mono}，不存在时为空 Mono
     */
    public static Mono<InvocationContext> current() {
        return Mono.deferContextual(contextView -> Mono.justOrEmpty(current(contextView)));
    }

    /**
     * 从当前 Reactor 上下文中获取 {@link InvocationContext}（必须存在）。
     *
     * @return 包含上下文的 {@link Mono}
     * @throws IllegalStateException 如果 Reactor 上下文中没有可用的 InvocationContext（通过 Mono.error 传播）
     */
    public static Mono<InvocationContext> currentRequired() {
        return Mono.deferContextual(contextView -> {
            InvocationContext context = current(contextView);
            if (context == null) {
                return Mono.error(new IllegalStateException("InvocationContext is not available in current reactive context"));
            }
            return Mono.just(context);
        });
    }

    /**
     * 在当前上下文下执行一个返回 {@link Mono} 的操作。
     * <p>
     * 等价于 {@code currentRequired().flatMap(action)}，简化样板代码。
     * </p>
     *
     * @param action 需要上下文的操作函数
     * @param <T>    返回值类型
     * @return 操作结果
     */
    public static <T> Mono<T> withCurrent(java.util.function.Function<InvocationContext, Mono<T>> action) {
        return currentRequired().flatMap(action);
    }

    /**
     * 在当前上下文下执行一个返回 {@link Flux} 的操作。
     * <p>
     * 等价于 {@code currentRequired().flatMapMany(action)}，简化样板代码。
     * </p>
     *
     * @param action 需要上下文的操作函数
     * @param <T>    元素类型
     * @return 操作结果
     */
    public static <T> Flux<T> withCurrentFlux(java.util.function.Function<InvocationContext, Flux<T>> action) {
        return currentRequired().flatMapMany(action);
    }

    public static InvocationContext current(ServerWebExchange exchange) {
        if (exchange == null) {
            return null;
        }
        Object attribute = exchange.getAttribute(InvocationContext.REQUEST_ATTRIBUTE);
        return attribute instanceof InvocationContext context ? context : null;
    }

    public static InvocationContext getOrCreate(ServerWebExchange exchange) {
        InvocationContext context = current(exchange);
        if (context != null) {
            return context;
        }
        ServerHttpRequest request = exchange.getRequest();
        String incomingSpanId = request.getHeaders().getFirst(InvocationContext.SPAN_HEADER);
        String parentSpanId = (incomingSpanId != null && !incomingSpanId.isBlank()) ? incomingSpanId : null;
        String remoteAddress = request.getRemoteAddress() != null ? request.getRemoteAddress().getAddress().getHostAddress() : null;
        String clientIp = ClientIpResolver.resolve(request.getHeaders()::getFirst, remoteAddress);
        InvocationContext created = InvocationContext.anonymous(
                InvocationContext.resolveId(request.getHeaders().getFirst(InvocationContext.TRACE_HEADER)),
                InvocationContext.generateId(),
                parentSpanId,
                InvocationContext.SOURCE_EXTERNAL,
                clientIp,
                request.getMethod() != null ? request.getMethod().name() : null,
                request.getPath().pathWithinApplication().value()
        );
        bind(exchange, created);
        return created;
    }

    public static void bind(ServerWebExchange exchange, InvocationContext context) {
        if (exchange != null && context != null) {
            exchange.getAttributes().put(InvocationContext.REQUEST_ATTRIBUTE, context);
        }
    }

    public static Function<Context, Context> contextWriter(InvocationContext context) {
        return reactorContext -> context != null ? reactorContext.put(InvocationContext.class, context) : reactorContext;
    }

    /**
     * 从指定的 {@link ContextView} 中提取 {@link InvocationContext}。
     * <p>
     * 供需要直接操作 {@link ContextView} 的场景（如自定义 WebFilter）使用。
     * </p>
     *
     * @param contextView Reactor 上下文视图
     * @return 上下文实例，不存在时返回 {@code null}
     */
    public static InvocationContext current(ContextView contextView) {
        if (contextView == null || !contextView.hasKey(InvocationContext.class)) {
            return null;
        }
        return contextView.get(InvocationContext.class);
    }
}
