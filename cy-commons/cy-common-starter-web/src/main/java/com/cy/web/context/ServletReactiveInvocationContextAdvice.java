package com.cy.web.context;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.context.InvocationScope;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Servlet MVC 返回值为 {@code Mono}/{@code Flux} 时的 {@link InvocationContext} 自动注入。
 * <p>
 * 在 Spring MVC 序列化响应体前拦截，若返回值是 {@link Publisher}，则：
 * <ol>
 *   <li>将当前 Servlet ThreadLocal 中的 {@link InvocationContext}
 *       通过 {@code contextWrite} 写入 Reactor {@link reactor.util.context.Context}。</li>
 *   <li>通过 {@link InvocationContextPublisher} 包装订阅过程，
 *       确保 {@code subscribe()} 时 ThreadLocal 中仍存在该上下文。</li>
 * </ol>
 * 配合 {@link ReactorInvocationContextBridge} 使用，可实现 Servlet/Reactor 混合环境下
 * {@link InvocationContext} 的全自动传播。
 * </p>
 *
 * @author pcy
 * @see ReactorInvocationContextBridge
 */
@ControllerAdvice
public class ServletReactiveInvocationContextAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /** 拦截响应体，对 {@link Publisher} 类型的返回值自动注入 {@link InvocationContext}。 */
    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (!(body instanceof Publisher<?> publisher)) {
            return body;
        }
        InvocationContext invocationContext = InvocationContextAccessor.current();
        if (invocationContext == null) {
            return body;
        }
        if (body instanceof Mono<?>) {
            return Mono.from(new InvocationContextPublisher<>(publisher, invocationContext))
                    .contextWrite(ReactiveInvocationContextAccessor.contextWriter(invocationContext));
        }
        return Flux.from(new InvocationContextPublisher<>(publisher, invocationContext))
                .contextWrite(ReactiveInvocationContextAccessor.contextWriter(invocationContext));
    }

    /**
     * 包装 {@link Publisher}，在 {@code subscribe()} 时临时恢复捕获的 {@link InvocationContext}
     * 到 {@link InvocationScope}，以保证订阅阶段的同步代码能读取到正确上下文。
     */
    private static final class InvocationContextPublisher<T> implements Publisher<T> {

        private final Publisher<T> delegate;
        private final InvocationContext invocationContext;

        private InvocationContextPublisher(Publisher<T> delegate, InvocationContext invocationContext) {
            this.delegate = delegate;
            this.invocationContext = invocationContext;
        }

        @Override
        public void subscribe(Subscriber<? super T> subscriber) {
            try (InvocationScope ignored = InvocationScope.open(invocationContext)) {
                delegate.subscribe(subscriber);
            }
        }
    }
}
