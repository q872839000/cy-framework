package com.cy.core.context;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 基于 {@link ThreadLocal} 栈的调用上下文作用域管理器。
 * <p>
 * 通过 try-with-resources 管理 {@link InvocationContext} 的生命周期：
 * <pre>{@code
 * try (InvocationScope ignored = InvocationScope.open(context)) {
 *     // 此范围内 InvocationScope.current() 返回 context
 * }
 * // 离开作用域后自动弹出
 * }</pre>
 * </p>
 * <h3>栈式设计</h3>
 * <p>
 * 支持嵌套作用域（如异步回调在原始请求上下文之上叠加系统上下文），
 * 关闭时只弹出当前层，不影响外层上下文。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 * @see InvocationContext
 * @see InvocationContextAccessor
 */
public final class InvocationScope implements AutoCloseable {

    /** 每线程维护一个上下文栈，栈顶为当前有效上下文 */
    private static final ThreadLocal<Deque<InvocationContext>> CONTEXT_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    /** 标记本作用域是否实际推入了上下文（context 为 null 时为 false） */
    private final boolean active;

    /**
     * 私有构造，仅由 {@link #open(InvocationContext)} 创建。
     *
     * @param active 是否实际推入了上下文
     */
    private InvocationScope(boolean active) {
        this.active = active;
    }

    /**
     * 打开一个新的上下文作用域，将给定上下文压入栈顶。
     * <p>
     * 如果 {@code context} 为 {@code null}，返回一个无操作的作用域，
     * 关闭时不会弹出任何上下文。
     * </p>
     *
     * @param context 要绑定的上下文，可为 {@code null}
     * @return 可用于 try-with-resources 的作用域句柄
     */
    public static InvocationScope open(InvocationContext context) {
        if (context == null) {
            return new InvocationScope(false);
        }
        CONTEXT_STACK.get().push(context);
        return new InvocationScope(true);
    }

    /**
     * 替换栈顶上下文（通常用于认证层升级匿名上下文为已认证上下文）。
     * <p>
     * 如果栈为空，直接压入；如果 {@code context} 为 {@code null}，不做操作。
     * </p>
     *
     * @param context 新的上下文
     */
    public static void replace(InvocationContext context) {
        if (context == null) {
            return;
        }
        Deque<InvocationContext> stack = CONTEXT_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        stack.push(context);
    }

    /**
     * 获取当前线程的栈顶上下文。
     *
     * @return 当前上下文，栈为空时返回 {@code null}
     */
    public static InvocationContext current() {
        Deque<InvocationContext> stack = CONTEXT_STACK.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 获取当前线程的栈顶上下文（必须存在）。
     *
     * @return 当前上下文
     * @throws IllegalStateException 如果当前没有可用的上下文
     */
    public static InvocationContext currentRequired() {
        InvocationContext context = current();
        if (context == null) {
            throw new IllegalStateException("InvocationContext is not available in current scope");
        }
        return context;
    }

    /**
     * 关闭作用域，弹出本作用域压入的上下文。
     * <p>
     * 如果弹出后栈为空，会清理 {@link ThreadLocal} 以避免内存泄漏。
     * </p>
     */
    @Override
    public void close() {
        if (!active) {
            return;
        }
        Deque<InvocationContext> stack = CONTEXT_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CONTEXT_STACK.remove();
        }
    }
}
