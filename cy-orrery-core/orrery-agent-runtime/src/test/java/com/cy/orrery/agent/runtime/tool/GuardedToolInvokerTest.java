// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.loop.ToolCallResult;
import com.cy.orrery.agent.runtime.loop.ToolInvoker;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带护栏的工具执行器单元测试（指纹熔断 / 失败升级 / 工具级事件）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class GuardedToolInvokerTest {

    private final List<RuntimeEvent> events = new ArrayList<>();

    private AgentRunContext newContext() {
        AgentRunContext ctx = new AgentRunContext("run-1",
                new InMemoryRuntimeEventBus("run-1"), null, null);
        ctx.getBus().addListener(events::add);
        return ctx;
    }

    private List<RuntimeEvent> eventsOf(RuntimeEventType type) {
        List<RuntimeEvent> found = new ArrayList<>();
        for (RuntimeEvent event : events) {
            if (event.type() == type) {
                found.add(event);
            }
        }
        return found;
    }

    /**
     * 相同参数连续失败达阈值后熔断拒绝；改参数自动解除。
     */
    @Test
    void shouldFuseOnRepeatedIdenticalFailureAndReleaseOnChangedArgs() {
        AtomicInteger executed = new AtomicInteger();
        ToolInvoker failing = (callId, toolName, arguments, ctx) -> {
            executed.incrementAndGet();
            return ToolCallResult.fail(callId, toolName, "E_FAIL", "失败", null);
        };
        try (GuardedToolInvoker guard = GuardedToolInvoker.wrap(failing,
                new ToolHarnessConfig(5000, 2, 3), ToolRoundHooks.NO_OP)) {
            AgentRunContext ctx = newContext();
            Map<String, Object> argsA = Map.of("k", "a");
            guard.invoke("c1", "deploy", argsA, ctx);
            guard.invoke("c2", "deploy", argsA, ctx);
            ToolCallResult fused = guard.invoke("c3", "deploy", argsA, ctx);

            assertFalse(fused.success());
            assertEquals("TOOL_ARG_FUSE", fused.errorCode());
            assertEquals(2, executed.get());  // 第 3 次被入口拒绝，未调工具

            // 改参数：指纹变化，熔断解除（工具被真实调用）
            ToolCallResult changed = guard.invoke("c4", "deploy", Map.of("k", "b"), ctx);
            assertEquals("E_FAIL", changed.errorCode());
            assertEquals(3, executed.get());
        }
    }

    /**
     * 工具执行超时返回 TOOL_TIMEOUT 失败回执。
     */
    @Test
    void shouldReturnTimeoutResult() {
        ToolInvoker slow = (callId, toolName, arguments, ctx) -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolCallResult.ok(callId, toolName, "{}", null);
        };
        try (GuardedToolInvoker guard = GuardedToolInvoker.wrap(slow,
                new ToolHarnessConfig(200, 2, 3), ToolRoundHooks.NO_OP)) {
            ToolCallResult result = guard.invoke("c1", "slow", Map.of(), newContext());
            assertFalse(result.success());
            assertEquals("TOOL_TIMEOUT", result.errorCode());
        }
    }

    /**
     * 单工具连续失败达阈值注入升级文案并发一次 milestone；成功清零。
     */
    @Test
    void shouldEscalateOnFailureStreak() {
        ToolInvoker failing = (callId, toolName, arguments, ctx) ->
                ToolCallResult.fail(callId, toolName, "E_FAIL", "失败", null);
        try (GuardedToolInvoker guard = GuardedToolInvoker.wrap(failing,
                new ToolHarnessConfig(5000, 0, 2), ToolRoundHooks.NO_OP)) {
            AgentRunContext ctx = newContext();
            guard.invoke("c1", "deploy", Map.of(), ctx);
            guard.invoke("c2", "deploy", Map.of(), ctx);
            ToolCallResult third = guard.invoke("c3", "deploy", Map.of(), ctx);

            assertTrue(third.errorMessage() != null && third.errorMessage().contains("连续失败"));
            assertEquals(1, eventsOf(RuntimeEventType.MILESTONE).size());
        }
    }

    /**
     * 工具级事件契约：每次调用发一对 TOOL_STARTED / TOOL_FINISHED，熔断拒绝不发。
     */
    @Test
    void shouldEmitToolEvents() {
        ToolInvoker ok = (callId, toolName, arguments, ctx) ->
                ToolCallResult.ok(callId, toolName, "{}", null);
        try (GuardedToolInvoker guard = GuardedToolInvoker.wrap(ok,
                new ToolHarnessConfig(5000, 0, 0), ToolRoundHooks.NO_OP)) {
            AgentRunContext ctx = newContext();
            guard.invoke("c1", "search", Map.of(), ctx);

            assertEquals(1, eventsOf(RuntimeEventType.TOOL_STARTED).size());
            assertEquals(1, eventsOf(RuntimeEventType.TOOL_FINISHED).size());
            assertEquals(Boolean.TRUE,
                    eventsOf(RuntimeEventType.TOOL_FINISHED).get(0).data().get("success"));
        }
    }
}
