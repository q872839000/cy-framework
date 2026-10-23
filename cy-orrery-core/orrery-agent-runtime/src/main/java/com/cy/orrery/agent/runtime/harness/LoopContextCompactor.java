// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;


import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.harness.impl.LoopMessageAdapter;
import com.cy.orrery.memory.context.ContextCompactor;
import java.util.List;
import java.util.Set;

/**
 * 循环消息折叠压缩器：折叠较早轮次的大体积 tool 消息正文。
 * <p>
 * 折叠语义：[boundary, keepFrom) 区间内未被钉扎的 tool 消息正文替换为长度占位符，
 * 保留消息骨架（role / toolCallId 配对协议不变）。LLM 语义压缩档（摘要式）
 * 由宿主在守望器之上扩展，本类只做确定性折叠。
 * </p>
 * <p>
 * 实现委托 orrery-memory 的通用折叠器（{@link ContextCompactor}），本类仅保留
 * agent-runtime 的 LoopMessage 适配与 API 形态，行为契约不变。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class LoopContextCompactor {

    private LoopContextCompactor() {
    }

    /**
     * 计算保留区起始下标：从尾部往回数第 {@code keepRounds} 个 assistant 消息的下标。
     * <p>
     * 一「轮」以 assistant 消息为界（ReAct 的一轮 = 一次 assistant 输出 + 其 tool 回执）。
     * 边界（boundary）之前的历史不再参与折叠计算。
     * </p>
     *
     * @param messages  消息列表
     * @param keepRounds 保留轮数（≥1）
     * @param boundary   已折叠边界
     * @return 保留区起始下标
     */
    public static int resolveKeepFromIndex(List<LoopMessage> messages, int keepRounds, int boundary) {
        return ContextCompactor.resolveKeepFromIndex(
                messages, LoopMessageAdapter.INSTANCE, keepRounds, boundary);
    }

    /**
     * 折叠 [boundary, keepFrom) 区间内未被钉扎的大体积 tool 消息正文。
     *
     * @param messages  消息列表（原地修改）
     * @param keepRounds 保留轮数
     * @param boundary   已折叠边界
     * @param pinned     钉扎下标集合
     * @return 折叠条数（0 表示无需折叠）
     */
    public static int compact(List<LoopMessage> messages, int keepRounds, int boundary, Set<Integer> pinned) {
        int keepFrom = resolveKeepFromIndex(messages, Math.max(1, keepRounds), Math.max(0, boundary));
        return compact(messages, boundary, pinned, keepFrom);
    }

    /**
     * 折叠 [boundary, keepFrom) 区间内未被钉扎的大体积 tool 消息正文（复用已算好的保留区起点）。
     *
     * @param messages 消息列表（原地修改）
     * @param boundary 已折叠边界
     * @param pinned   钉扎下标集合
     * @param keepFrom 保留区起始下标（经 {@link #resolveKeepFromIndex} 计算）
     * @return 折叠条数（0 表示无需折叠）
     */
    public static int compact(List<LoopMessage> messages, int boundary, Set<Integer> pinned, int keepFrom) {
        return ContextCompactor.compact(
                messages, LoopMessageAdapter.INSTANCE, boundary, pinned, keepFrom);
    }
}
