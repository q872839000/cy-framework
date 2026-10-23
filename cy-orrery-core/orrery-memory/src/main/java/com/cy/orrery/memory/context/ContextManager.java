// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.core.exception.CYException;

/**
 * 上下文管理器（L0 门面）。
 * <p>
 * stateful 模式：{@link #open} 按 sessionId 恢复/新建窗口并加活跃锁，
 * 同 session 并发 open 抛 50622（一个会话同时只允许一个活跃窗口）。
 * stateless 模式：宿主自持消息列表，经 {@link #windowManager} 取纯算法门面。
 * </p>
 *
 * @author pcy
 */
public class ContextManager {

    /** 窗口配置。 */
    private final ContextWindowConfig config;

    /** 窗口存储（null = stateless 或存储不可用，窗口仅 run 内存活）。 */
    private final ContextWindowStore store;

    /** 滚动摘要器。 */
    private final RollingSummarizer summarizer;

    /**
     * 构造上下文管理器。
     *
     * @param config     窗口配置（null 用默认值）
     * @param store      窗口存储（null = stateless，窗口仅 run 内存活）
     * @param summarizer 滚动摘要器
     */
    public ContextManager(ContextWindowConfig config, ContextWindowStore store,
                          RollingSummarizer summarizer) {
        this.config = config == null ? ContextWindowConfig.defaults() : config;
        this.store = store;
        this.summarizer = summarizer;
    }

    /**
     * 打开会话窗口（stateful）。
     *
     * @param sessionId 会话 ID
     * @return 上下文会话
     * @throws CYException 同 session 已有活跃窗口（50622）
     */
    public ContextSession open(String sessionId) {
        ContextWindow window = ContextWindow.empty();
        if (store != null) {
            if (!store.tryAcquire(sessionId)) {
                throw new CYException(50622, sessionId);
            }
            window = store.load(sessionId).orElseGet(ContextWindow::empty);
        }
        return new DefaultContextSession(sessionId, config, window, store, summarizer);
    }

    /**
     * 只读查看会话滚动摘要（不加活跃锁；注入装配用）。
     *
     * @param sessionId 会话 ID
     * @return 滚动摘要（无/过期为 null）
     */
    public String peekSummary(String sessionId) {
        if (store == null) {
            return null;
        }
        return store.load(sessionId).map(ContextWindow::getSummary).orElse(null);
    }

    /**
     * 构造无状态窗口管理器（宿主自持消息列表的纯算法模式）。
     *
     * @param adapter 宿主消息适配器
     * @param <T>     宿主消息类型
     * @return 窗口管理器
     */
    public <T> ContextWindowManager<T> windowManager(MessageAdapter<T> adapter) {
        return new ContextWindowManager<>(config, adapter);
    }

    /**
     * 窗口是否可跨 run 恢复。
     *
     * @return 持久化可用性
     */
    public boolean windowPersistent() {
        return store != null && store.available();
    }

    /**
     * 窗口配置。
     *
     * @return 配置
     */
    public ContextWindowConfig config() {
        return config;
    }
}
