// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存上下文窗口存储（开发/测试兜底；进程重启全丢，不提供 TTL）。
 *
 * @author pcy
 */
public class InMemoryContextWindowStore implements ContextWindowStore {

    /** 快照表。 */
    private final Map<String, ContextWindow> windows = new ConcurrentHashMap<>();

    /** 活跃锁表。 */
    private final Map<String, Boolean> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<ContextWindow> load(String sessionId) {
        return Optional.ofNullable(windows.get(sessionId));
    }

    @Override
    public boolean save(String sessionId, ContextWindow window, long expectedVersion) {
        ContextWindow current = windows.get(sessionId);
        if (current == null && expectedVersion != 0) {
            return false;
        }
        if (current != null && current.getVersion() != expectedVersion) {
            return false;
        }
        windows.put(sessionId, window);
        return true;
    }

    @Override
    public boolean tryAcquire(String sessionId) {
        return locks.putIfAbsent(sessionId, Boolean.TRUE) == null;
    }

    @Override
    public void release(String sessionId) {
        locks.remove(sessionId);
    }

    @Override
    public boolean available() {
        return true;
    }
}
