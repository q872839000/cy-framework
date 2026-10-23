// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.orrery.memory.support.FakeChatPort;
import com.cy.orrery.memory.prompt.PromptResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文会话决策流测试（两级抢救 + 持久化 + 并发护栏）。
 *
 * @author pcy
 */
class DefaultContextSessionTest {

    /** 小窗口配置：1000 token，常规档 0.5，硬档 0.9，保留 2 轮。 */
    private ContextWindowConfig smallWindow() {
        return new ContextWindowConfig(1000, 0.5, 0.9, 2);
    }

    private ContextManager newManager(InMemoryContextWindowStore store, FakeChatPort chat) {
        return new ContextManager(smallWindow(), store,
                new RollingSummarizer(chat, new PromptResolver()));
    }

    @Test
    void underThresholdDoesNothing() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        ContextManager manager = newManager(store, new FakeChatPort());
        try (ContextSession session = manager.open("s1")) {
            session.append(List.of(
                    ChatMessage.of(ChatMessage.ROLE_USER, "你好"),
                    ChatMessage.of(ChatMessage.ROLE_ASSISTANT, "你好，请说")));
            List<ChatMessage> ready = session.prepareForLlm();
            assertEquals(2, ready.size());
            assertFalse(ready.get(0).isFolded());
        }
    }

    @Test
    void foldsToolMessagesBeforeSummarizing() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        FakeChatPort chat = new FakeChatPort().reply("摘要：早期对话");
        ContextManager manager = newManager(store, chat);
        try (ContextSession session = manager.open("s2")) {
            List<ChatMessage> batch = new ArrayList<>();
            // 4 轮对话，每轮带一条 tool 消息（总量超常规档 500 但低于硬档 900）
            for (int i = 0; i < 4; i++) {
                batch.add(ChatMessage.of(ChatMessage.ROLE_USER, "问题" + i));
                batch.add(ChatMessage.of(ChatMessage.ROLE_TOOL, "x".repeat(600)));
                batch.add(ChatMessage.of(ChatMessage.ROLE_ASSISTANT, "回答" + i));
            }
            session.append(batch);
            List<ChatMessage> ready = session.prepareForLlm();
            long foldedCount = ready.stream().filter(ChatMessage::isFolded).count();
            assertTrue(foldedCount >= 1, "常规阈值触发后应折叠旧 tool 消息");
            // 折叠只碰保留区外：最后 2 轮的 tool 消息保持原文
            assertFalse(ready.get(ready.size() - 2).isFolded());
        }
    }

    @Test
    void evictsOldestRoundsIntoSummaryWhenFoldingIsNotEnough() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        FakeChatPort chat = new FakeChatPort().handler(user -> "LLM摘要结果");
        ContextManager manager = newManager(store, chat);
        try (ContextSession session = manager.open("s3")) {
            List<ChatMessage> batch = new ArrayList<>();
            // 10 轮纯 user/assistant 大消息（无 tool 可折叠，必须走驱逐+摘要）
            for (int i = 0; i < 10; i++) {
                batch.add(ChatMessage.of(ChatMessage.ROLE_USER, "用户问题第" + i + "轮" + "长".repeat(200)));
                batch.add(ChatMessage.of(ChatMessage.ROLE_ASSISTANT, "助手回答第" + i + "轮" + "长".repeat(200)));
            }
            session.append(batch);
            List<ChatMessage> ready = session.prepareForLlm();
            assertEquals("LLM摘要结果", session.currentSummary());
            // 摘要消息在首位
            assertEquals(ChatMessage.ROLE_SYSTEM, ready.get(0).getRole());
            assertEquals("LLM摘要结果", ready.get(0).getContent());
            // 保留区 2 轮原文仍在
            assertTrue(ready.stream().anyMatch(m -> m.getContent() != null
                    && m.getContent().contains("第9轮")));
        }
    }

    @Test
    void closePersistsWindowAndNextOpenRestores() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        FakeChatPort chat = new FakeChatPort().handler(user -> "持久化摘要");
        ContextManager manager = newManager(store, chat);
        try (ContextSession session = manager.open("s4")) {
            List<ChatMessage> batch = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                batch.add(ChatMessage.of(ChatMessage.ROLE_USER, "问" + i + "长".repeat(200)));
                batch.add(ChatMessage.of(ChatMessage.ROLE_ASSISTANT, "答" + i + "长".repeat(200)));
            }
            session.append(batch);
            session.prepareForLlm();
        }
        try (ContextSession restored = manager.open("s4")) {
            assertEquals("持久化摘要", restored.currentSummary());
        }
    }

    @Test
    void concurrentOpenIsRejected() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        ContextManager manager = newManager(store, new FakeChatPort());
        ContextSession first = manager.open("s5");
        try {
            assertThrows(com.cy.core.exception.CYException.class, () -> manager.open("s5"));
        } finally {
            first.close();
        }
        // close 释放锁后可再次 open
        manager.open("s5").close();
    }

    @Test
    void llmFailureFallsBackToRawConcat() {
        InMemoryContextWindowStore store = new InMemoryContextWindowStore();
        FakeChatPort chat = new FakeChatPort().handler(user -> {
            throw new RuntimeException("LLM down");
        });
        ContextManager manager = newManager(store, chat);
        try (ContextSession session = manager.open("s6")) {
            List<ChatMessage> batch = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                batch.add(ChatMessage.of(ChatMessage.ROLE_USER, "问题" + i + "长".repeat(200)));
                batch.add(ChatMessage.of(ChatMessage.ROLE_ASSISTANT, "回答" + i + "长".repeat(200)));
            }
            session.append(batch);
            session.prepareForLlm();
            // 降级拼接：摘要非空且包含被驱逐内容
            String summary = session.currentSummary();
            assertTrue(summary != null && summary.contains("用户："), "降级摘要应含驱逐段原文");
            assertNotEquals("LLM摘要结果", summary);
        }
    }
}
