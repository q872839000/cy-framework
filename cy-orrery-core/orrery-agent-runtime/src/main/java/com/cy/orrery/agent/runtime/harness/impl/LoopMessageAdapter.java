// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness.impl;

import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.memory.context.MessageAdapter;
import com.cy.orrery.memory.context.TokenEstimator;

/**
 * LoopMessage 的公共上下文子系统适配器。
 * <p>
 * 折叠只发生在 tool 消息上，故 {@link #withContent} 保留 role/toolCallId
 * 重建 tool 消息（配对协议不变）。assistant 的 tool_calls 参数按正文累计附加 token。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class LoopMessageAdapter implements MessageAdapter<LoopMessage> {

    /** 共享单例。 */
    public static final LoopMessageAdapter INSTANCE = new LoopMessageAdapter();

    @Override
    public String role(LoopMessage message) {
        return message.role();
    }

    @Override
    public String content(LoopMessage message) {
        return message.content();
    }

    @Override
    public LoopMessage withContent(LoopMessage message, String newContent) {
        // 折叠只作用于 tool 消息：保留 toolCallId 配对
        return LoopMessage.tool(message.toolCallId(), newContent);
    }

    @Override
    public int extraTokens(LoopMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return 0;
        }
        int total = 0;
        for (LoopToolCall call : message.toolCalls()) {
            total += TokenEstimator.estimate(call.name())
                    + TokenEstimator.estimate(call.arguments()) + 4;
        }
        return total;
    }
}
