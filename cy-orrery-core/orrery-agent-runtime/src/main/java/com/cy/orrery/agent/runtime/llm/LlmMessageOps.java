// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import java.util.List;

/**
 * LLM 调用装饰器的消息历史尾部操作工具。
 * <p>
 * 重试 / 续接 / 结构化解析等装饰器都会在调用前后挂载临时消息（半截 assistant、
 * 续接指令、解析错误反馈），返回前必须撤除——同一"撤掉尾部 N 条"逻辑原先在
 * {@code TruncationContinuationDecorator} 与 {@code StructuredOutputInvoker}
 * 各写一份，收敛于此（DRY，撤除语义演进只改一处）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/28
 */
public final class LlmMessageOps {

    private LlmMessageOps() {
    }

    /**
     * 撤掉消息历史尾部的 count 条临时消息。
     *
     * @param ctx   运行上下文
     * @param count 撤除条数（实际撤除不超过现存条数）
     */
    public static void removeLastMessages(AgentRunContext ctx, int count) {
        List<LoopMessage> messages = ctx.getMessages();
        for (int i = 0; i < count && !messages.isEmpty(); i++) {
            messages.remove(messages.size() - 1);
        }
    }
}
