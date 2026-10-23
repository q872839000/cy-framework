// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.support;

import com.cy.orrery.memory.api.ChatPort;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Function;

/**
 * 测试用对话端口（脚本化输出：固定回复队列或函数式回复）。
 *
 * @author pcy
 */
public class FakeChatPort implements ChatPort {

    /** 固定回复队列（按调用顺序出队）。 */
    private final Queue<String> replies = new ArrayDeque<>();

    /** 函数式回复（优先级高于队列）。 */
    private Function<String, String> handler;

    /** 可用开关。 */
    private boolean available = true;

    /** 追加固定回复。 */
    public FakeChatPort reply(String text) {
        replies.add(text);
        return this;
    }

    /** 设置函数式回复（入参为 user 消息）。 */
    public FakeChatPort handler(Function<String, String> handler) {
        this.handler = handler;
        return this;
    }

    /** 设置可用性。 */
    public FakeChatPort available(boolean available) {
        this.available = available;
        return this;
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        if (handler != null) {
            return handler.apply(userMessage);
        }
        return replies.isEmpty() ? "" : replies.poll();
    }

    @Override
    public boolean available() {
        return available;
    }
}
