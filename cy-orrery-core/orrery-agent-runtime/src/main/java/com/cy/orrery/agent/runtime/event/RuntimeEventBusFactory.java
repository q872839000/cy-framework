// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;

import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 运行事件总线工厂：创建总线并自动挂载全局监听器。
 * <p>
 * 事件总线是 per-run 对象（携带 runId）；桥接器（AgentEvent 线协议）、
 * 观测埋点（Micrometer）这类「每次运行都要在环上」的组件注册为全局监听器，
 * 由工厂在创建总线时统一挂载——宿主不必在每个运行入口处手工 attach。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class RuntimeEventBusFactory {

    private final List<Consumer<RuntimeEvent>> globalListeners = new CopyOnWriteArrayList<>();

    /**
     * 创建默认工厂（无全局监听器）。
     */
    public RuntimeEventBusFactory() {
    }

    /**
     * 注册全局监听器（之后创建的总线都会挂载；已创建的不追溯）。
     *
     * @param listener 监听器
     */
    public void addGlobalListener(Consumer<RuntimeEvent> listener) {
        globalListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 移除全局监听器。
     *
     * @param listener 监听器
     * @return true 表示移除成功
     */
    public boolean removeGlobalListener(Consumer<RuntimeEvent> listener) {
        return globalListeners.remove(listener);
    }

    /**
     * 为一次运行创建事件总线（挂载全部全局监听器）。
     *
     * @param runId 运行 ID
     * @return 事件总线
     */
    public RuntimeEventBus createBus(String runId) {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus(runId);
        globalListeners.forEach(bus::addListener);
        return bus;
    }
}
