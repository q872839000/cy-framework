// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event.impl;


import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程内事件总线默认实现。
 * <p>
 * {@code seq} 由 {@link AtomicLong} 单调递增分配；监听器采用 COW 列表，
 * 发射线程同步回调（顺序性与低延迟优先，监听器内禁止长阻塞）。
 * 单个监听器异常被隔离（记录 error 日志后继续派发后续监听器），
 * 不影响其它监听器与引擎主流程。跨实例 / 跨进程的事件分发由宿主服务
 * 在外层适配（如 Redis 广播），本实现不承担。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class InMemoryRuntimeEventBus implements RuntimeEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRuntimeEventBus.class);

    private final String runId;
    private final AtomicLong seqCursor = new AtomicLong();
    private final List<Consumer<RuntimeEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;
    private volatile String cancelReason;

    /**
     * 构造进程内事件总线。
     *
     * @param runId 运行 ID（用于事件回填）
     */
    public InMemoryRuntimeEventBus(String runId) {
        this.runId = Objects.requireNonNull(runId, "runId");
    }

    @Override
    public RuntimeEvent emit(RuntimeEventType type, Map<String, Object> data) {
        Map<String, Object> payload = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(data));
        RuntimeEvent event = new RuntimeEvent(seqCursor.incrementAndGet(), type, runId,
                payload, type.terminal(), System.currentTimeMillis());
        for (Consumer<RuntimeEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // 监听器故障隔离：单监听器异常不阻断后续监听器，也不侵入引擎主流程。
                log.error("事件监听器执行异常 runId={} seq={} type={} listener={}",
                        runId, event.seq(), type, listener.getClass().getName(), e);
            }
        }
        return event;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public String cancellationReason() {
        return cancelReason;
    }

    @Override
    public void cancel(String reason) {
        this.cancelled = true;
        this.cancelReason = reason;
    }

    @Override
    public void addListener(Consumer<RuntimeEvent> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public boolean removeListener(Consumer<RuntimeEvent> listener) {
        return listeners.remove(listener);
    }
}
