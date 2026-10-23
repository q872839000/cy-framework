// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 运行时事件总线。
 * <p>
 * 单次运行内的事件通道：负责 {@code seq} 分配、监听器分发与取消信号。
 * 取消信号同时承担「事件通道取消」与「运行取消检查点」两个职责——
 * 引擎在关键节点通过 {@link #isCancelled()} 检查并抛出取消异常。
 * </p>
 * <p>
 * 实现必须保证 {@link #emit} 线程安全（{@code seq} 单调、监听器遍历无并发修改异常）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface RuntimeEventBus {

    /**
     * 发射事件。
     *
     * @param type 事件类型
     * @param data 事件数据；{@code null} 视为空 Map
     * @return 已发射的事件（含分配的 seq）
     */
    RuntimeEvent emit(RuntimeEventType type, Map<String, Object> data);

    /**
     * 发射无数据事件。
     *
     * @param type 事件类型
     * @return 已发射的事件
     */
    default RuntimeEvent emit(RuntimeEventType type) {
        return emit(type, Map.of());
    }

    /**
     * 当前运行是否已被请求取消。
     *
     * @return true 表示已取消
     */
    boolean isCancelled();

    /**
     * 取消原因（未取消时为 {@code null}）。
     *
     * @return 取消原因
     */
    String cancellationReason();

    /**
     * 请求取消当前运行。
     *
     * @param reason 取消原因
     */
    void cancel(String reason);

    /**
     * 注册事件监听器（每次发射时同步回调）。
     *
     * @param listener 监听器
     */
    void addListener(Consumer<RuntimeEvent> listener);

    /**
     * 移除事件监听器。
     *
     * @param listener 监听器
     * @return true 表示移除成功
     */
    boolean removeListener(Consumer<RuntimeEvent> listener);
}
