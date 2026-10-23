// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.memory;

import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.assemble.MemoryContextAssembler;
import com.cy.orrery.memory.context.ContextManager;
import com.cy.orrery.memory.context.ContextWindowConfig;
import com.cy.orrery.memory.context.InMemoryContextWindowStore;
import com.cy.orrery.memory.context.RollingSummarizer;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.cy.orrery.memory.semantic.ConsolidationService;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆运行桥测试（注入装配 + 异步巩固 + 安全降级）。
 *
 * @author pcy
 */
class MemoryRunBridgeTest {

    private ContextManager newContextManager() {
        return new ContextManager(ContextWindowConfig.defaults(),
                new InMemoryContextWindowStore(), new RollingSummarizer(null, new PromptResolver()));
    }

    @Test
    void injectionIsEmptyWhenNoSubsystemAssembled() {
        MemoryRunBridge bridge = new MemoryRunBridge(new MemoryRunBridge.Dependencies(
                new MemoryContextAssembler(null, null, null, null),
                newContextManager(), null, null, null));
        Optional<String> text = bridge.buildStartupInjection(
                MemoryScope.ofUser(null, "u1"), "s1", "查询");
        assertTrue(text.isEmpty());
        bridge.close();
    }

    @Test
    void injectionIncludesPersistedSessionSummary() throws Exception {
        ContextManager contextManager = newContextManager();
        // 先跑一个会话写入摘要（LLM 缺席 → 降级原文拼接），close 落库
        ContextWindowConfig small = new ContextWindowConfig(200, 0.5, 0.9, 1);
        ContextManager tinyManager = new ContextManager(small, new InMemoryContextWindowStore(),
                new RollingSummarizer(null, new PromptResolver()));
        try (var session = tinyManager.open("s2")) {
            List<com.cy.orrery.memory.context.ChatMessage> batch = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                batch.add(com.cy.orrery.memory.context.ChatMessage.of("user", "问题" + i + "长".repeat(50)));
                batch.add(com.cy.orrery.memory.context.ChatMessage.of("assistant", "回答" + i + "长".repeat(50)));
            }
            session.append(batch);
            session.prepareForLlm();
        }
        MemoryRunBridge bridge = new MemoryRunBridge(new MemoryRunBridge.Dependencies(
                new MemoryContextAssembler(null, null, null, null), tinyManager, null, null, null));
        Optional<String> text = bridge.buildStartupInjection(
                MemoryScope.ofUser(null, "u1"), "s2", "继续");
        assertTrue(text.isPresent());
        assertTrue(text.get().contains("<session-summary>"));
        bridge.close();
    }

    @Test
    void runCompletionTriggersAsyncConsolidation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        ConsolidationService recording = new ConsolidationService(
                new ConsolidationService.Dependencies(null, null, null, null, null, 0.92, 0, null)) {
            @Override
            public int consolidate(MemoryScope scope, String transcript, String sourceRef) {
                calls.incrementAndGet();
                latch.countDown();
                return 0;
            }
        };
        MemoryRunBridge bridge = new MemoryRunBridge(new MemoryRunBridge.Dependencies(
                null, newContextManager(), recording, null, null));
        bridge.onRunCompleted(MemoryScope.ofUser(null, "u1"), "s3", "r1", List.of(
                LoopMessage.user("记住这个"), LoopMessage.assistant("好的", null, null)));
        assertTrue(latch.await(5, TimeUnit.SECONDS), "巩固应在后台执行");
        assertEquals(1, calls.get());
        bridge.close();
    }

    @Test
    void runCompletionDegradesSafelyWhenNothingAssembled() {
        MemoryRunBridge bridge = new MemoryRunBridge(new MemoryRunBridge.Dependencies(
                null, newContextManager(), null, null, null));
        bridge.onRunCompleted(MemoryScope.ofUser(null, "u1"), "s4", "r2",
                List.of(LoopMessage.user("你好")));
        bridge.close();
    }
}
