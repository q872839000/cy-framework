// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;


import com.cy.orrery.agent.runtime.context.LoopMessage;
import java.util.List;
import java.util.Set;

/**
 * 消息钉扎 SPI：压缩时「不可折叠消息」的领域判定注入点。
 * <p>
 * 钉扎语义：活跃使用中的证据（如修复中的问题依据、被后续轮次引用的工作产物）
 * 折叠后会导致 LLM 遗忘重查，领域据此返回需保留的消息下标集合。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
@FunctionalInterface
public interface MessagePinner {

    /**
     * 计算钉扎集合。
     *
     * @param messages 消息列表
     * @param keepFrom 保留区起始下标（该下标起的消息不参与折叠，无需钉扎）
     * @return 钉扎的消息下标集合（[0, keepFrom) 区间内不被折叠的下标）
     */
    Set<Integer> pinnedIndexes(List<LoopMessage> messages, int keepFrom);

    /**
     * 空实现（无钉扎语义时使用）。
     */
    MessagePinner NO_PIN = (messages, keepFrom) -> Set.of();
}
