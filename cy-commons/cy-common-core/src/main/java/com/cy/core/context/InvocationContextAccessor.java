package com.cy.core.context;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 调用上下文访问器（同步 / 阻塞环境专用）。
 * <p>
 * 提供两类能力：
 * <ol>
 *   <li><b>读取</b> — {@link #current()} / {@link #currentRequired()}，业务 Service 通过此方法隐式获取上下文</li>
 *   <li><b>传播</b> — {@link #wrap(Runnable)} / {@link #wrap(Callable)}，
 *       将当前上下文捕获后包裹到异步任务中，确保跨线程上下文不丢失</li>
 * </ol>
 * </p>
 * <h3>使用场景</h3>
 * <ul>
 *   <li>Service 内部读取当前用户：{@code InvocationContextAccessor.currentRequired().getUserId()}</li>
 *   <li>异步任务装饰器：{@code executor.setTaskDecorator(InvocationContextAccessor::wrap)}</li>
 *   <li>边界层显式传播：{@code InvocationContextAccessor.wrap(context, () -> service.doSomething())}</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/06
 * @see InvocationScope
 * @see com.cy.web.context.ReactiveInvocationContextAccessor
 */
public final class InvocationContextAccessor {

    /** 工具类，禁止实例化 */
    private InvocationContextAccessor() {
    }

    /**
     * 获取当前线程的调用上下文。
     *
     * @return 当前上下文，不存在时返回 {@code null}
     */
    public static InvocationContext current() {
        return InvocationScope.current();
    }

    /**
     * 获取当前线程的调用上下文（必须存在）。
     *
     * @return 当前上下文
     * @throws IllegalStateException 如果当前没有可用的上下文
     */
    public static InvocationContext currentRequired() {
        return InvocationScope.currentRequired();
    }

    public static InvocationContext currentOr(InvocationContext fallback) {
        InvocationContext context = current();
        return context != null ? context : fallback;
    }

    public static InvocationContext currentOrSystem() {
        return currentOr(InvocationContext.system());
    }

    public static Long currentUserId() {
        InvocationContext context = current();
        return context != null ? context.getUserId() : null;
    }

    public static Long currentUserIdRequired() {
        InvocationContext context = currentRequired();
        return context.getUserId();
    }

    /**
     * 将当前上下文捕获后包裹到 {@link Runnable} 中，用于异步传播。
     *
     * @param task 原始任务
     * @return 携带上下文的包装任务
     */
    public static Runnable wrap(Runnable task) {
        return wrap(current(), task);
    }

    /**
     * 将指定上下文包裹到 {@link Runnable} 中。
     * <p>
     * 适用于边界层（如 Butler Facade）需要显式绑定上下文的场景。
     * </p>
     *
     * @param context 要传播的上下文
     * @param task    原始任务
     * @return 携带上下文的包装任务
     */
    public static Runnable wrap(InvocationContext context, Runnable task) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try (InvocationScope ignored = InvocationScope.open(context)) {
                task.run();
            } finally {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    public static void run(InvocationContext context, Runnable task) {
        wrap(context, task).run();
    }

    /**
     * 将当前上下文捕获后包裹到 {@link Callable} 中，用于异步传播。
     *
     * @param task 原始任务
     * @param <T>  返回值类型
     * @return 携带上下文的包装任务
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return wrap(current(), task);
    }

    /**
     * 将指定上下文包裹到 {@link Callable} 中。
     *
     * @param context 要传播的上下文
     * @param task    原始任务
     * @param <T>     返回值类型
     * @return 携带上下文的包装任务
     */
    public static <T> Callable<T> wrap(InvocationContext context, Callable<T> task) {
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            try (InvocationScope ignored = InvocationScope.open(context)) {
                return task.call();
            } finally {
                if (previousMdc != null) {
                    MDC.setContextMap(previousMdc);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    public static <T> T call(InvocationContext context, Callable<T> task) throws Exception {
        return wrap(context, task).call();
    }
}
