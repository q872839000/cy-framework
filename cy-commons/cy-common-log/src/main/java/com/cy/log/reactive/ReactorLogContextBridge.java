package com.cy.log.reactive;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationScope;
import com.cy.log.access.AccessLogContext;
import com.cy.log.access.AccessTraceState;
import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reactor Context 与日志 ThreadLocal 的桥接器。
 * <p>
 * 日志组件内部仍有部分同步式访问入口（如 {@link InvocationScope} 与 {@link AccessLogContext}），
 * 该桥接器在每个 Reactor 信号执行前临时恢复：
 * </p>
 * <ul>
 *     <li>{@link InvocationContext}</li>
 *     <li>{@link AccessTraceState}</li>
 *     <li>MDC 中的 traceId/spanId/parentSpanId</li>
 * </ul>
 * <p>
 * 这样可以让响应式链路中的日志切面与编程式日志复用同一套上下文访问模型。
 * </p>
 *
 * @author pcy
 * @date 2026/04/14
 */
public class ReactorLogContextBridge implements DisposableBean {

    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_SPAN_ID = "spanId";
    private static final String MDC_PARENT_SPAN_ID = "parentSpanId";
    private static final String HOOK_KEY = ReactorLogContextBridge.class.getName();

    public ReactorLogContextBridge() {
        Hooks.onEachOperator(HOOK_KEY,
                Operators.liftPublisher((publisher, subscriber) -> new ContextBridgeSubscriber<>(subscriber)));
    }

    @Override
    public void destroy() {
        Hooks.resetOnEachOperator(HOOK_KEY);
    }

    private static InvocationContext getInvocationContext(ContextView contextView) {
        return contextView.hasKey(InvocationContext.class)
                ? contextView.get(InvocationContext.class)
                : null;
    }

    private static AccessTraceState getAccessTraceState(ContextView contextView) {
        return contextView.hasKey(AccessTraceState.class)
                ? contextView.get(AccessTraceState.class)
                : null;
    }

    private static void withContext(ContextView contextView, Runnable task) {
        withContext(contextView, () -> {
            task.run();
            return null;
        });
    }

    private static <T> T withContext(ContextView contextView, Supplier<T> supplier) {
        InvocationContext invocationContext = getInvocationContext(contextView);
        AccessTraceState accessTraceState = getAccessTraceState(contextView);
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        InvocationContext currentContext = InvocationScope.current();
        InvocationContext targetContext = Objects.equals(currentContext, invocationContext) ? null : invocationContext;
        try (InvocationScope ignored = InvocationScope.open(targetContext);
             AccessLogContext.Scope accessScope = AccessLogContext.open(accessTraceState)) {
            ReactorLogContextBridge.applyMdc(invocationContext);
            return supplier.get();
        } finally {
            ReactorLogContextBridge.restoreMdc(previousMdc);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Subscription wrapSubscription(Subscription subscription, ContextView contextView) {
        if (subscription instanceof Fuseable.QueueSubscription<?> queueSubscription) {
            return new ContextBridgeQueueSubscription<>((Fuseable.QueueSubscription<T>) queueSubscription, contextView);
        }
        return new ContextBridgeSubscription(subscription, contextView);
    }

    private static void applyMdc(InvocationContext invocationContext) {
        if (invocationContext == null) {
            return;
        }
        putOrRemove(MDC_TRACE_ID, invocationContext.getTraceId());
        putOrRemove(MDC_SPAN_ID, invocationContext.getSpanId());
        putOrRemove(MDC_PARENT_SPAN_ID, invocationContext.getParentSpanId());
    }

    private static void putOrRemove(String key, String value) {
        if (value == null || value.isBlank()) {
            MDC.remove(key);
            return;
        }
        MDC.put(key, value);
    }

    private static void restoreMdc(Map<String, String> previousMdc) {
        if (previousMdc != null) {
            MDC.setContextMap(previousMdc);
        } else {
            MDC.clear();
        }
    }

    /**
     * 为单个 Reactor Subscriber 恢复日志上下文。
     */
    private static final class ContextBridgeSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> actual;

        private ContextBridgeSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
        }

        @Override
        public Context currentContext() {
            return actual.currentContext();
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            withContext(() -> actual.onSubscribe(wrapSubscription(subscription, currentContext())));
        }

        @Override
        public void onNext(T t) {
            withContext(() -> actual.onNext(t));
        }

        @Override
        public void onError(Throwable throwable) {
            withContext(() -> actual.onError(throwable));
        }

        @Override
        public void onComplete() {
            withContext(actual::onComplete);
        }

        private void withContext(Runnable task) {
            ReactorLogContextBridge.withContext(currentContext(), task);
        }
    }

    /**
     * 包装 Subscription.cancel()，保证取消信号也在正确的日志上下文中执行。
     */
    private static final class ContextBridgeSubscription implements Subscription {

        private final Subscription delegate;
        private final ContextView contextView;

        private ContextBridgeSubscription(Subscription delegate, ContextView contextView) {
            this.delegate = delegate;
            this.contextView = contextView;
        }

        @Override
        public void request(long n) {
            delegate.request(n);
        }

        @Override
        public void cancel() {
            ReactorLogContextBridge.withContext(contextView, delegate::cancel);
        }
    }

    private static final class ContextBridgeQueueSubscription<T> extends AbstractQueue<T>
            implements Fuseable.QueueSubscription<T> {

        private final Fuseable.QueueSubscription<T> delegate;
        private final ContextView contextView;

        private ContextBridgeQueueSubscription(Fuseable.QueueSubscription<T> delegate, ContextView contextView) {
            this.delegate = delegate;
            this.contextView = contextView;
        }

        @Override
        public int requestFusion(int requestedMode) {
            return delegate.requestFusion(requestedMode);
        }

        @Override
        public void request(long n) {
            delegate.request(n);
        }

        @Override
        public void cancel() {
            ReactorLogContextBridge.withContext(contextView, delegate::cancel);
        }

        @Override
        public T poll() {
            return ReactorLogContextBridge.withContext(contextView, delegate::poll);
        }

        @Override
        public T peek() {
            return ReactorLogContextBridge.withContext(contextView, delegate::peek);
        }

        @Override
        public Iterator<T> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(T value) {
            return delegate.offer(value);
        }

        @Override
        public boolean isEmpty() {
            return ReactorLogContextBridge.withContext(contextView, delegate::isEmpty);
        }

        @Override
        public void clear() {
            ReactorLogContextBridge.withContext(contextView, delegate::clear);
        }
    }
}
