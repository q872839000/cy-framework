package com.cy.web.context;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.context.InvocationScope;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Reactor 与 {@link InvocationScope} 的自动桥接器。
 * <p>
 * 职责：
 * <ul>
 *   <li>通过 {@link Hooks#onEachOperator} 在每个 Reactor 信号执行前，
 *       将 Reactor {@link Context} 中的 {@link InvocationContext} 恢复到 {@link InvocationScope}（ThreadLocal），
 *       同时在订阅时把当前 ThreadLocal 中的上下文写入 Reactor Context（仅当 Context 中不存在时）。</li>
 *   <li>通过 {@link Schedulers#onScheduleHook} 在 Reactor 调度器切换线程时，
 *       自动以 {@link InvocationContextAccessor#wrap(Runnable)} 传播上下文。</li>
 * </ul>
 * 配合 {@link ServletReactiveInvocationContextAdvice}，可让 Servlet MVC 控制器返回 {@code Mono}/{@code Flux} 时
 * 对调用方完全透明地传播 {@link InvocationContext}。
 * </p>
 *
 * @author pcy
 * @see ServletReactiveInvocationContextAdvice
 * @see InvocationScope
 */
public class ReactorInvocationContextBridge implements DisposableBean {

    private static final String OPERATOR_HOOK_KEY = ReactorInvocationContextBridge.class.getName() + ".operator";
    private static final String SCHEDULE_HOOK_KEY = ReactorInvocationContextBridge.class.getName() + ".schedule";

    public ReactorInvocationContextBridge() {
        Hooks.onEachOperator(OPERATOR_HOOK_KEY,
                Operators.liftPublisher((publisher, subscriber) -> new ContextBridgeSubscriber<>(subscriber)));
        Schedulers.onScheduleHook(SCHEDULE_HOOK_KEY, InvocationContextAccessor::wrap);
    }

    @Override
    public void destroy() {
        Hooks.resetOnEachOperator(OPERATOR_HOOK_KEY);
        Schedulers.resetOnScheduleHook(SCHEDULE_HOOK_KEY);
    }

    /**
     * 从 Reactor {@link ContextView} 中提取 {@link InvocationContext}。
     */
    private static InvocationContext getInvocationContext(ContextView contextView) {
        return contextView.hasKey(InvocationContext.class)
                ? contextView.get(InvocationContext.class)
                : null;
    }

    /**
     * 如果 Reactor Context 中尚不存在 {@link InvocationContext}，
     * 则从当前 ThreadLocal 捕获并写入 Context（Servlet → Reactor 方向注入）。
     */
    private static Context mergeInvocationContext(Context currentContext) {
        InvocationContext invocationContext = InvocationContextAccessor.current();
        if (invocationContext == null || currentContext.hasKey(InvocationContext.class)) {
            return currentContext;
        }
        return currentContext.put(InvocationContext.class, invocationContext);
    }

    private static void withContext(ContextView contextView, Runnable task) {
        withContext(contextView, () -> {
            task.run();
            return null;
        });
    }

    /**
     * 在执行 {@code supplier} 前，将 Reactor Context 中的 {@link InvocationContext}
     * 临时恢复到 {@link InvocationScope}（Reactor → ThreadLocal 方向回放）。
     * <p>
     * 如果 ThreadLocal 中已是同一 {@link InvocationContext} 实例，则跳过压栈，避免冗余嵌套。
     * </p>
     */
    private static <T> T withContext(ContextView contextView, Supplier<T> supplier) {
        InvocationContext invocationContext = getInvocationContext(contextView);
        InvocationContext currentContext = InvocationScope.current();
        InvocationContext targetContext = Objects.equals(currentContext, invocationContext) ? null : invocationContext;
        try (InvocationScope ignored = InvocationScope.open(targetContext)) {
            return supplier.get();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Subscription wrapSubscription(Subscription subscription, ContextView contextView) {
        if (subscription instanceof Fuseable.QueueSubscription<?> queueSubscription) {
            return new ContextBridgeQueueSubscription<>((Fuseable.QueueSubscription<T>) queueSubscription, contextView);
        }
        return new ContextBridgeSubscription(subscription, contextView);
    }

    /**
     * 包装 {@link CoreSubscriber}，在每个信号回调前恢复 {@link InvocationContext} 到 ThreadLocal。
     */
    private static final class ContextBridgeSubscriber<T> implements CoreSubscriber<T> {

        private final CoreSubscriber<? super T> actual;
        private final Context context;

        private ContextBridgeSubscriber(CoreSubscriber<? super T> actual) {
            this.actual = actual;
            this.context = mergeInvocationContext(actual.currentContext());
        }

        @Override
        public Context currentContext() {
            return context;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            withContext(() -> actual.onSubscribe(wrapSubscription(subscription, context)));
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
            ReactorInvocationContextBridge.withContext(context, task);
        }
    }

    /**
     * 包装 {@link Subscription}，保证 {@code cancel()} 信号也在正确的上下文中执行。
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
            ReactorInvocationContextBridge.withContext(contextView, delegate::cancel);
        }
    }

    /**
     * 包装 {@link Fuseable.QueueSubscription}，为 fusion 模式下的 poll/peek 等操作恢复上下文。
     */
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
            ReactorInvocationContextBridge.withContext(contextView, delegate::cancel);
        }

        @Override
        public T poll() {
            return ReactorInvocationContextBridge.withContext(contextView, delegate::poll);
        }

        @Override
        public T peek() {
            return ReactorInvocationContextBridge.withContext(contextView, delegate::peek);
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
            return ReactorInvocationContextBridge.withContext(contextView, delegate::isEmpty);
        }

        @Override
        public void clear() {
            ReactorInvocationContextBridge.withContext(contextView, delegate::clear);
        }
    }
}
