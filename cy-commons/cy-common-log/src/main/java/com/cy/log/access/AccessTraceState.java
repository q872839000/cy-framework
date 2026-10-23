package com.cy.log.access;

import com.cy.core.context.InvocationContext;
import lombok.Getter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单次调用链路的 ACCESS 状态快照。
 * <p>
 * 该对象聚合一次入口调用在 ACCESS 维度上的全部运行态，包括：
 * </p>
 * <ul>
 *     <li>主采样结果</li>
 *     <li>明细采样结果</li>
 *     <li>发布时使用的 {@link InvocationContext}</li>
 *     <li>当前未完成 span 栈</li>
 *     <li>按调用顺序记录的完整 span 列表</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/04/14
 */
@Getter
public class AccessTraceState {

    /**
     * 本次链路是否被主采样命中。
     */
    private final boolean sampled;

    /**
     * 本次链路是否命中明细采样。
     */
    private final boolean detailSampled;

    /**
     * 发布 ACCESS 日志时使用的调用上下文快照。
     */
    private final InvocationContext invocationContext;

    /**
     * 尚未完成的 span 栈。
     */
    private final Deque<AccessSpan> stack = new ArrayDeque<>(8);

    /**
     * 按调用顺序记录的 span 列表。
     */
    private final List<AccessSpan> orderList = new ArrayList<>(8);

    /**
     * 防止同一条 trace 被重复发布。
     */
    private final AtomicBoolean published = new AtomicBoolean(false);
    private int asyncTaskCount;
    private Runnable publishAction;

    public AccessTraceState(boolean sampled, boolean detailSampled, InvocationContext invocationContext) {
        this.sampled = sampled;
        this.detailSampled = detailSampled;
        this.invocationContext = invocationContext;
    }

    /**
     * 尝试将状态标记为已发布。
     *
     * @return 首次标记成功时返回 {@code true}
     */
    public boolean markPublished() {
        return published.compareAndSet(false, true);
    }

    public synchronized void reserveAsyncTask() {
        asyncTaskCount++;
    }

    public synchronized void releaseAsyncTask() {
        if (asyncTaskCount > 0) {
            asyncTaskCount--;
        }
    }

    public synchronized boolean hasPendingAsyncTask() {
        return asyncTaskCount > 0;
    }

    public synchronized void setPublishAction(Runnable publishAction) {
        this.publishAction = publishAction;
    }

    public void tryPublishIfReady() {
        Runnable action;
        synchronized (this) {
            if (!stack.isEmpty() || asyncTaskCount > 0 || !published.compareAndSet(false, true)) {
                return;
            }
            action = publishAction;
        }
        if (action != null) {
            action.run();
        }
    }

    /**
     * 清空运行态集合。
     */
    public void clear() {
        stack.clear();
        orderList.clear();
        asyncTaskCount = 0;
        publishAction = null;
    }
}
