package com.cy.log.access;

import com.cy.core.context.InvocationContext;

import java.util.List;

/**
 * 调用链路日志上下文
 * <p>
 * 当前实现不再直接把多个运行态拆散到多个 ThreadLocal，
 * 而是统一维护一个 {@link AccessTraceState}：
 * <ul>
 *     <li>同步路径下将状态绑定到当前线程</li>
 *     <li>响应式路径下通过 Reactor Context 传播同一份状态</li>
 *     <li>在信号回调中再临时恢复到 ThreadLocal，兼容既有同步访问模型</li>
 * </ul>
 * <p>
 * 该设计避免了旧实现中「调用栈、深度、顺序列表」分散存储造成的响应式上下文割裂问题。
 * </p>
 *
 * @author pcy
 * @date 2026/04/09
 */
public final class AccessLogContext {

    /**
     * 最大调用栈深度，防止无限递归导致的内存泄漏
     */
    private static final int MAX_STACK_DEPTH = 50;

    /**
     * 当前线程绑定的 ACCESS trace 状态。
     */
    private static final ThreadLocal<AccessTraceState> STATE = new ThreadLocal<>();

    private AccessLogContext() {
    }

    /**
     * 创建新的 trace 状态对象。
     */
    public static AccessTraceState createState(boolean sampled,
                                               boolean detailSampled,
                                               InvocationContext invocationContext) {
        return new AccessTraceState(sampled, detailSampled, invocationContext);
    }

    /**
     * 获取当前线程绑定的 trace 状态。
     */
    public static AccessTraceState currentState() {
        return STATE.get();
    }

    /**
     * 将 trace 状态绑定到当前线程。
     */
    public static void bind(AccessTraceState state) {
        if (state == null) {
            STATE.remove();
            return;
        }
        STATE.set(state);
    }

    /**
     * 打开一个临时作用域，将指定状态绑定到当前线程，并在关闭时恢复先前状态。
     */
    public static Scope open(AccessTraceState state) {
        AccessTraceState previous = STATE.get();
        if (state == null) {
            STATE.remove();
        } else {
            STATE.set(state);
        }
        return new Scope(previous);
    }

    /**
     * 创建新的 AccessSpan 并压入调用栈
     * <p>
     * 每个 span 在创建时即分配独立 {@code spanId}：
     * 根 span 的 {@code parentSpanId} 继承入口调用上下文，
     * 子 span 的 {@code parentSpanId} 指向父 span 的 {@code spanId}。
     * 这样运行态与持久化态共用同一套身份模型，无需在发布阶段再做推断。
     * </p>
     *
     * @return 新创建的 AccessSpan；未采样或栈深度超限时返回 {@code null}
     */
    public static AccessSpan push() {
        AccessTraceState state = STATE.get();
        if (state == null || !state.isSampled()) {
            return null;
        }
        synchronized (state) {
            if (state.getStack().size() >= MAX_STACK_DEPTH) {
                return null;
            }
            AccessSpan parent = state.getStack().peekLast();
            AccessSpan span = new AccessSpan();
            span.setParent(parent);
            span.setSpanId(InvocationContext.generateId());
            span.setParentSpanId(parent != null
                    ? parent.getSpanId()
                    : state.getInvocationContext() != null ? state.getInvocationContext().getParentSpanId() : null);
            span.setDepth(parent != null ? parent.getDepth() + 1 : 1);
            state.getStack().offerLast(span);
            state.getOrderList().add(span);
            return span;
        }
    }

    /**
     * 获取当前栈顶的 AccessSpan（不弹出）
     *
     * @return 当前 span，如果栈为空返回 null
     */
    public static AccessSpan peek() {
        AccessTraceState state = STATE.get();
        if (state == null) {
            return null;
        }
        synchronized (state) {
            return state.getStack().peekLast();
        }
    }

    /**
     * 完成指定 span 并从未完成栈中移除。
     * <p>
     * span 完成后，会将当前 span 的总耗时累加到直接父级 span 的子方法耗时中，
     * 以便计算父级的「实际执行耗时」。
     */
    public static void complete(AccessSpan span) {
        AccessTraceState state = STATE.get();
        if (state == null || span == null) {
            return;
        }
        synchronized (state) {
            if (!span.hasPendingAsyncContinuation()) {
                removeCompletedSpan(state, span);
            }
        }
    }

    /**
     * 判断当前线程的所有调用是否已全部完成（栈为空）
     */
    public static boolean isComplete() {
        AccessTraceState state = STATE.get();
        if (state == null) {
            return true;
        }
        synchronized (state) {
            return state.getStack().isEmpty();
        }
    }

    /**
     * 获取当前线程记录的所有 span（按调用顺序）
     */
    public static List<AccessSpan> getAllSpans() {
        AccessTraceState state = STATE.get();
        if (state == null) {
            return List.of();
        }
        synchronized (state) {
            return List.copyOf(state.getOrderList());
        }
    }

    /**
     * 清空当前线程的所有上下文数据
     * <p>
     * 必须在最外层方法完成后调用，防止 ThreadLocal 内存泄漏
     */
    public static void clear() {
        AccessTraceState state = STATE.get();
        if (state != null) {
            synchronized (state) {
                state.clear();
            }
        }
    }

    /**
     * 获取当前线程的有效调用深度。
     *
     * @return 当前深度，无上下文时返回 0
     */
    public static int currentDepth() {
        AccessSpan span = peek();
        return span != null ? span.getDepth() : 0;
    }

    /**
     * 包装 Runnable，使其在执行时继承当前线程绑定的 {@link AccessTraceState}。
     * <p>
     * 适用于 {@code Thread.startVirtualThread} / 线程池等跨线程场景，
     * 确保子线程中的 ACCESS 逻辑仍能加入同一条 trace。
     * <pre>{@code
     * Thread.startVirtualThread(
     *     AccessLogContext.wrapWithParentDepth(
     *         InvocationContextAccessor.wrap(() -> {
     *             // 此处能继续访问父线程绑定的 AccessTraceState
     *         })
     *     )
     * );
     * }</pre>
     * <p>
     * 方法名保留为 {@code wrapWithParentDepth} 仅为兼容既有调用点，
     * 当前实际传播的是整条 {@link AccessTraceState}。
     * </p>
     *
     * @param task 原始任务
     * @return 携带当前 trace 状态的包装任务
     */
    public static Runnable wrapWithParentDepth(Runnable task) {
        AccessTraceState state = currentState();
        if (state == null) {
            return task;
        }
        AccessSpan parentSpan = peek();
        state.reserveAsyncTask();
        if (parentSpan != null) {
            parentSpan.reserveAsyncContinuation();
        }
        return () -> {
            try (Scope ignored = open(state)) {
                try {
                    task.run();
                } finally {
                    synchronized (state) {
                        if (parentSpan != null
                                && parentSpan.releaseAsyncContinuation()
                                && parentSpan.isCompleted()) {
                            removeCompletedSpan(state, parentSpan);
                        }
                        state.releaseAsyncTask();
                    }
                    state.tryPublishIfReady();
                }
            }
        };
    }

    private static void removeCompletedSpan(AccessTraceState state, AccessSpan span) {
        boolean removed = state.getStack().removeLastOccurrence(span);
        if (removed && span.getParent() != null) {
            span.getParent().addChildDuration(span.getDuration());
        }
    }

    /**
     * 彻底移除 ThreadLocal 引用（线程池场景下推荐）
     */
    public static void remove() {
        STATE.remove();
    }

    /**
     * AccessTraceState 的临时绑定作用域。
     */
    public static final class Scope implements AutoCloseable {

        private final AccessTraceState previous;

        private Scope(AccessTraceState previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                STATE.remove();
                return;
            }
            STATE.set(previous);
        }
    }
}
