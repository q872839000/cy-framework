// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.List;
import java.util.Set;

/**
 * 上下文折叠器（第一级抢救：保留区外的 tool 大消息换占位符，不调 LLM）。
 * <p>
 * 折叠契约：只碰 tool 消息，user/assistant 永不折叠（摘要原料零污染）；
 * 钉扎下标集合豁免折叠（活跃证据不丢失）；已折叠消息幂等跳过。
 * 一「轮」以 assistant 消息为界（ReAct 的一轮 = 一次 assistant 输出 + 其 tool 回执）。
 * </p>
 *
 * @author pcy
 */
public final class ContextCompactor {

    private ContextCompactor() {
    }

    /**
     * 解析保留区起点（从尾部数最近 keepRounds 个 assistant 消息的下标）。
     *
     * @param messages   消息列表
     * @param adapter    宿主适配器
     * @param keepRounds 保留轮次
     * @param boundary   已折叠边界（之前的段不再考虑）
     * @param <T>        宿主消息类型
     * @return 保留区起始下标（保留区外的旧段才可折叠）
     */
    public static <T> int resolveKeepFromIndex(List<T> messages, MessageAdapter<T> adapter,
                                               int keepRounds, int boundary) {
        int keepFrom = messages.size();
        int rounds = 0;
        for (int i = messages.size() - 1; i >= boundary; i--) {
            if (ChatMessage.ROLE_ASSISTANT.equals(adapter.role(messages.get(i)))) {
                rounds++;
                if (rounds >= keepRounds) {
                    keepFrom = i;
                    break;
                }
            }
        }
        // 轮次数不足保留轮次 → 整个列表都在保留区内
        return rounds < keepRounds ? messages.size() : Math.max(keepFrom, boundary);
    }

    /**
     * 执行折叠（保留区外、未钉扎、未折叠且正文达标的 tool 消息换占位符）。
     *
     * @param messages 消息列表（折叠实例写回原位）
     * @param adapter  宿主适配器
     * @param boundary 已折叠边界
     * @param pinned   钉扎下标集合
     * @param keepFrom 保留区起点
     * @param <T>      宿主消息类型
     * @return 本次折叠的消息数
     */
    public static <T> int compact(List<T> messages, MessageAdapter<T> adapter, int boundary,
                                  Set<Integer> pinned, int keepFrom) {
        int elided = 0;
        Set<Integer> safePinned = pinned == null ? Set.of() : pinned;
        for (int i = Math.max(0, boundary); i < keepFrom && i < messages.size(); i++) {
            if (safePinned.contains(i)) {
                continue;
            }
            T message = messages.get(i);
            if (!ChatMessage.ROLE_TOOL.equals(adapter.role(message)) || adapter.isFolded(message)) {
                continue;
            }
            String content = adapter.content(message);
            if (content == null || content.length() < TokenEstimator.minFoldLength()) {
                continue;
            }
            messages.set(i, adapter.fold(message, content.length()));
            elided++;
        }
        return elided;
    }
}
