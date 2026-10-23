// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context.impl;

import com.cy.orrery.agent.runtime.context.CancellationRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内取消注册表默认实现。
 * <p>
 * 取消原因分表维护：cancel 写入原因，isCancelled 只看取消表。
 * register / unregister 不再登记活跃集——SPI 无「是否已注册」查询，
 * 登记是只写状态（不可观测、不可恢复、白占内存），删除；
 * 活跃运行的观测经 AgentRunner 的 activeRuns 计数承担。
 * 跨实例取消（多副本部署）由宿主服务经分布式存储适配。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class InMemoryCancellationRegistry implements CancellationRegistry {

    private final Map<String, String> cancelledReasons = new ConcurrentHashMap<>();

    @Override
    public void register(String runId) {
        // 无操作：见类 javadoc（注册登记为只写状态，已删）
    }

    @Override
    public void cancel(String runId, String reason) {
        cancelledReasons.put(runId, reason == null ? "" : reason);
    }

    @Override
    public boolean isCancelled(String runId) {
        return cancelledReasons.containsKey(runId);
    }

    @Override
    public void unregister(String runId) {
        cancelledReasons.remove(runId);
    }
}
