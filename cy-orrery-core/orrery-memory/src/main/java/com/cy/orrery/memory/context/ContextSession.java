// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.List;

/**
 * 上下文会话（L0 有状态窗口的使用入口，run 生命周期内持有）。
 * <p>
 * 使用契约：open → append（只追加新消息，不重传全量历史）
 * → 每次调 LLM 前 prepareForLlm → close（写回窗口快照并释放活跃锁）。
 * </p>
 *
 * @author pcy
 */
public interface ContextSession extends AutoCloseable {

    /** 摘要消息在返回列表中的标记 ID。 */
    String SUMMARY_MESSAGE_ID = "memory-rolling-summary";

    /**
     * 会话 ID。
     *
     * @return 会话 ID
     */
    String sessionId();

    /**
     * 追加本轮新增消息。
     *
     * @param messages 新消息
     */
    void append(List<ChatMessage> messages);

    /**
     * 窗口守望（每次调 LLM 前）：估算 → 折叠 → 必要时驱逐+滚动摘要，
     * 返回可直接发送的消息列表（摘要在首位，标记 {@link #SUMMARY_MESSAGE_ID}）。
     *
     * @return 可发送消息列表
     */
    List<ChatMessage> prepareForLlm();

    /**
     * 当前滚动摘要（注入装配用；无则 null）。
     *
     * @return 摘要
     */
    String currentSummary();

    /**
     * 窗口内消息视图（只读副本）。
     *
     * @return 消息列表
     */
    List<ChatMessage> messages();

    /**
     * 写回窗口快照并释放活跃锁（幂等，重复调用安全）。
     */
    @Override
    void close();
}
