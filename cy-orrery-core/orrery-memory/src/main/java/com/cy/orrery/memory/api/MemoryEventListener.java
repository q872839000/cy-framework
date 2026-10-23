// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * 记忆事件监听 SPI（指标埋点/观测钩子；实现必须无副作用、异常自吞）。
 *
 * @author pcy
 */
public interface MemoryEventListener {

    /**
     * 写入完成。
     *
     * @param item 条目
     */
    default void onMemoryWritten(MemoryItem item) {
    }

    /**
     * 语义召回完成。
     *
     * @param scope    归属范围
     * @param hitCount 命中数
     * @param elapsedMs 耗时毫秒
     */
    default void onRecalled(MemoryScope scope, int hitCount, long elapsedMs) {
    }

    /**
     * 能力降级（如 LLM 不可用导致摘要降级原文拼接）。
     *
     * @param path 降级点标识（固定枚举值，禁带用户数据）
     */
    default void onDegraded(String path) {
    }
}
