// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.harness.impl.DefaultContextGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认上下文守望单元测试（双档折叠 / 钉扎保护 / 阈值判定）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class DefaultContextGuardTest {

    private final List<RuntimeEvent> events = new ArrayList<>();

    private AgentRunContext contextOf(List<LoopMessage> messages) {
        AgentRunContext ctx = new AgentRunContext("run-1",
                new InMemoryRuntimeEventBus("run-1"), null, messages);
        ctx.getBus().addListener(events::add);
        return ctx;
    }

    /**
     * 构造 4 轮消息（每轮 assistant + 长 tool 回执）。
     *
     * @return 消息列表
     */
    private static List<LoopMessage> fourRounds() {
        List<LoopMessage> messages = new ArrayList<>();
        for (int round = 0; round < 4; round++) {
            messages.add(LoopMessage.assistant("round " + round, null,
                    List.of(new LoopToolCall("c" + round, "search", "{}"))));
            messages.add(LoopMessage.tool("c" + round, "x".repeat(200)));
        }
        return messages;
    }

    private List<RuntimeEvent> tracesOf(String kind) {
        List<RuntimeEvent> found = new ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event.type() == RuntimeEventType.SYSTEM_TRACE
                    && kind.equals(event.data().get("kind"))) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * 常规档压缩：超阈值折叠较早轮次，保留最近 keepRounds 轮。
     */
    @Test
    void shouldCompactOnNormalTier() {
        DefaultContextGuard guard = new DefaultContextGuard(
                ContextGuardConfig.of(400, 0.5, 0.92, 2), MessagePinner.NO_PIN);
        AgentRunContext ctx = contextOf(fourRounds());

        int after = guard.prepareForLlmCall(ctx);

        // 4 轮消息估算约 232 token > 阈值 200 → 触发常规档
        assertEquals(1, tracesOf("context.compacted").size());
        assertEquals("normal", tracesOf("context.compacted").get(0).data().get("tier"));
        // 下标 1、3 的旧 tool 回执被折叠，下标 5（最近第 2 轮内）保留
        assertTrue(ctx.getMessages().get(1).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertTrue(ctx.getMessages().get(3).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertFalse(ctx.getMessages().get(5).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertTrue(after < 232);
    }

    /**
     * 钉扎保护：被钉扎的消息不折叠。
     */
    @Test
    void shouldProtectPinnedMessages() {
        MessagePinner pinner = (messages, keepFrom) -> Set.of(1);
        DefaultContextGuard guard = new DefaultContextGuard(
                ContextGuardConfig.of(400, 0.5, 0.92, 2), pinner);
        AgentRunContext ctx = contextOf(fourRounds());

        guard.prepareForLlmCall(ctx);

        assertEquals(1, tracesOf("context.compacted").size());
        // 下标 1 被钉扎保留原文，下标 3 折叠
        assertFalse(ctx.getMessages().get(1).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertTrue(ctx.getMessages().get(3).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
    }

    /**
     * 硬保护档：超硬阈值折叠至最近 1 轮。
     */
    @Test
    void shouldHardCompactToSingleRound() {
        // 估算约 232 token：阈值 120 触发、硬阈值 0.92×240≈221 亦超 → 硬档
        DefaultContextGuard guard = new DefaultContextGuard(
                ContextGuardConfig.of(240, 0.5, 0.92, 3), MessagePinner.NO_PIN);
        AgentRunContext ctx = contextOf(fourRounds());

        guard.prepareForLlmCall(ctx);

        assertEquals(1, tracesOf("context.compacted").size());
        assertEquals("hard", tracesOf("context.compacted").get(0).data().get("tier"));
        // 硬档保留最近 1 轮：下标 5 也被折叠
        assertTrue(ctx.getMessages().get(5).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertFalse(ctx.getMessages().get(7).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
    }

    /**
     * 阈值以下不压缩。
     */
    @Test
    void shouldSkipWhenBelowThreshold() {
        DefaultContextGuard guard = new DefaultContextGuard(
                ContextGuardConfig.of(10000, 0.5, 0.92, 2), MessagePinner.NO_PIN);
        AgentRunContext ctx = contextOf(fourRounds());

        int after = guard.prepareForLlmCall(ctx);

        assertTrue(tracesOf("context.compacted").isEmpty());
        assertFalse(ctx.getMessages().get(1).content().startsWith(com.cy.orrery.memory.context.MessageAdapter.FOLD_PLACEHOLDER_PREFIX));
        assertTrue(after > 200);
    }
}
