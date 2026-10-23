// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;

/**
 * 运行取消注册表。
 * <p>
 * 跨总线 / 跨线程的取消信号源：HTTP 取消端点、超时守护线程等外部信号
 * 经注册表写入，引擎在检查点查询。与 {@code RuntimeEventBus.cancel} 相互独立——
 * 事件总线的取消只覆盖「事件通道持有者」，注册表覆盖「任意持有 runId 的信号源」。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface CancellationRegistry {

    /**
     * 注册运行（开始追踪）。
     *
     * @param runId 运行 ID
     */
    void register(String runId);

    /**
     * 请求取消运行。
     *
     * @param runId  运行 ID
     * @param reason 取消原因
     */
    void cancel(String runId, String reason);

    /**
     * 查询运行是否已被取消。
     *
     * @param runId 运行 ID
     * @return true 表示已取消
     */
    boolean isCancelled(String runId);

    /**
     * 注销运行（结束后清理）。
     *
     * @param runId 运行 ID
     */
    void unregister(String runId);
}
