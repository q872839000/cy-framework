// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构化输出调用器测试：解析重试、临时消息撤除、选项透传。
 *
 * @author pcy
 * @since 2026/08/27
 */
class StructuredOutputInvokerTest {

    /** 脚本化 LLM（记录收到的 options）。 */
    static final class FakeLlm implements LlmInvoker {

        final Deque<LlmInvoker.LlmRoundResult> script = new ArrayDeque<>();
        LlmCallOptions lastOptions;

        @Override
        public List<Map<String, Object>> toolSpecs() {
            return List.of();
        }

        @Override
        public LlmInvoker.LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
            return invoke(ctx, roundIndex, toolSpecs, null);
        }

        @Override
        public LlmInvoker.LlmRoundResult invoke(AgentRunContext ctx, int roundIndex,
                                     List<Map<String, Object>> toolSpecs, LlmCallOptions options) {
            lastOptions = options;
            LlmInvoker.LlmRoundResult next = script.pollFirst();
            return next == null ? LlmInvoker.LlmRoundResult.ok("{}", null, List.of(), 1, 1) : next;
        }
    }

    private AgentRunContext newContext() {
        return new AgentRunContext("run-s1", new InMemoryRuntimeEventBus("run-s1"), null,
                new java.util.ArrayList<>(List.of(LoopMessage.user("给我 JSON"))));
    }

    @Test
    void retriesWithFeedbackUntilValidJson() {
        FakeLlm llm = new FakeLlm();
        llm.script.add(LlmInvoker.LlmRoundResult.ok("这不是 JSON", null, List.of(), 1, 1));
        llm.script.add(LlmInvoker.LlmRoundResult.ok("{\"a\": 1}", null, List.of(), 1, 1));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(llm, new ObjectMapper());
        AgentRunContext ctx = newContext();

        StructuredOutputInvoker.StructuredResult result = invoker.invokeJson(ctx, LlmCallOptions.jsonObject());

        assertTrue(result.ok());
        assertEquals(1, result.json().get("a").asInt());
        assertEquals(1, ctx.getMessages().size(), "临时反馈消息必须在返回前撤除");
        assertNotNull(llm.lastOptions);
        assertEquals("json_object", llm.lastOptions.responseFormat().get("type"));
    }

    @Test
    void toleratesMarkdownFence() {
        FakeLlm llm = new FakeLlm();
        llm.script.add(LlmInvoker.LlmRoundResult.ok("```json\n{\"b\": 2}\n```", null, List.of(), 1, 1));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(llm, new ObjectMapper());

        StructuredOutputInvoker.StructuredResult result = invoker.invokeJson(newContext(), null);

        assertTrue(result.ok());
        assertEquals(2, result.json().get("b").asInt());
    }

    @Test
    void failsAfterBoundedRetries() {
        FakeLlm llm = new FakeLlm();
        llm.script.add(LlmInvoker.LlmRoundResult.ok("bad1", null, List.of(), 1, 1));
        llm.script.add(LlmInvoker.LlmRoundResult.ok("bad2", null, List.of(), 1, 1));
        llm.script.add(LlmInvoker.LlmRoundResult.ok("bad3", null, List.of(), 1, 1));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(llm, new ObjectMapper(), 2);
        AgentRunContext ctx = newContext();

        StructuredOutputInvoker.StructuredResult result = invoker.invokeJson(ctx, LlmCallOptions.jsonObject());

        assertTrue(!result.ok());
        assertNull(result.json());
        assertNotNull(result.error());
        assertEquals(1, ctx.getMessages().size());
    }
}
