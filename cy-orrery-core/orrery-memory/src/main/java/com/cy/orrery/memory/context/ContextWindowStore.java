// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.core.exception.CYException;
import java.util.Optional;

/**
 * 上下文窗口存储 SPI（stateful 模式的持久化后端）。
 * <p>
 * 语义契约：save 携带期望版本做 CAS，冲突返回 false 由调用方决策；
 * tryAcquire/release 是同 session 串行护栏（一个会话同时只允许一个活跃窗口）。
 * </p>
 *
 * @author pcy
 */
public interface ContextWindowStore {

    /**
     * 读取窗口快照（不存在/TTL 过期返回 empty）。
     *
     * @param sessionId 会话 ID
     * @return 快照
     */
    Optional<ContextWindow> load(String sessionId);

    /**
     * 乐观锁写回（期望版本不匹配返回 false）。
     *
     * @param sessionId       会话 ID
     * @param window          快照
     * @param expectedVersion 期望版本
     * @return 是否写回成功
     */
    boolean save(String sessionId, ContextWindow window, long expectedVersion);

    /**
     * 获取会话活跃锁（同 session 串行）。
     *
     * @param sessionId 会话 ID
     * @return 是否获得（false = 已有活跃窗口）
     * @throws CYException 存储故障
     */
    boolean tryAcquire(String sessionId);

    /**
     * 释放会话活跃锁。
     *
     * @param sessionId 会话 ID
     */
    void release(String sessionId);

    /**
     * 是否可用（不可用时窗口退化为仅 run 内存活）。
     *
     * @return 可用性
     */
    boolean available();
}
