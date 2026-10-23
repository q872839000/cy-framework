// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;


import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.harness.impl.LoopMessageAdapter;
import com.cy.orrery.memory.context.TokenEstimator;
import java.util.List;

/**
 * 循环消息 token 估算器（粗估口径，领域无关）。
 * <p>
 * 估算规则：CJK 字符按 1 token/字、其它字符按 4 字符 ≈ 1 token；
 * assistant 消息的 tool_calls 参数按正文累计。深度思考内容（reasoning）
 * 通常不回传进下一轮 prompt，不计入。估算结果只用于压缩守望触发判定，
 * 精确用量以 {@code LlmRoundResult} 的统计为准。
 * </p>
 * <p>
 * 字符级估算与最小折叠长度委托 orrery-memory 的
 * {@link TokenEstimator}（实现一致），本类保留 LoopMessage 级估算
 * （含 tool_calls 附加）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class LoopTokenEstimator {

    private LoopTokenEstimator() {
    }

    /**
     * 估算文本 token 数。
     *
     * @param text 文本（可空）
     * @return 估算 token 数
     */
    public static int estimate(String text) {
        return TokenEstimator.estimate(text);
    }

    /**
     * 估算消息列表的总 token 数。
     *
     * @param messages 消息列表
     * @return 估算 token 数
     */
    public static int estimate(List<LoopMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (LoopMessage message : messages) {
            total += LoopMessageAdapter.INSTANCE.estimateTokens(message);
        }
        return total;
    }

    /**
     * 折叠判定用：正文长度低于该值不折叠（折叠收益低于占位成本）。
     *
     * @return 最小折叠长度
     */
    public static int minFoldLength() {
        return TokenEstimator.minFoldLength();
    }
}
