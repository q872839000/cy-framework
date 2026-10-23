// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具轮引擎单元测试（审批屏障 / 暂缓重放 / 部分失败语义）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class LoopToolRoundEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 事件收集容器（newContext 时挂监听，保证时序正确）。 */
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

    private static LoopToolCall call(String id, String name) {
        return new LoopToolCall(id, name, "{}");
    }

    /**
     * 审批屏障：首个审批命中后，同轮后续调用暂缓并写占位回执。
     */
    @Test
    void shouldSuspendSubsequentCallsAfterApprovalBarrier() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) ->
                ToolCallResult.ok(callId, toolName, "{\"ok\":true}", "动作完成");
        ToolCallInspector inspector = new ToolCallInspector() {
            @Override
            public ApprovalRequest inspectApproval(ToolCallResult result) {
                return "t1".equals(result.callId())
                        ? new ApprovalRequest("apv-1", "高危操作", result.callId(), List.of())
                        : null;
            }

            @Override
            public ClarificationRequest inspectClarification(ToolCallResult result) {
                return null;
            }
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker, inspector, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(call("t1", "deploy"), call("t2", "deploy")), ToolRoundHooks.NO_OP);

        assertTrue(result.suspend());
        assertEquals(AgentStop.NEEDS_APPROVAL, acc.stop);
        assertEquals(1, LoopAttributes.deferredCalls(ctx).size());
        assertEquals("t2", LoopAttributes.deferredCalls(ctx).get(0).callId());
        assertEquals(1, eventsOf(RuntimeEventType.APPROVAL_REQUIRED).size());
        assertEquals("apv-1", eventsOf(RuntimeEventType.APPROVAL_REQUIRED).get(0).data().get("approvalId"));
    }

    /**
     * 审批通过后的确定性重放：真实回执覆盖占位回执，完成后清空暂缓。
     */
    @Test
    void shouldReplayDeferredCallsAndClear() {
        AtomicInteger executed = new AtomicInteger();
        ToolInvoker invoker = (callId, toolName, arguments, ctx) -> {
            executed.incrementAndGet();
            return ToolCallResult.ok(callId, toolName, "{\"replayed\":true}", null);
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, MAPPER);
        AgentRunContext ctx = newContext();
        ctx.addMessage(LoopMessage.tool("t2", "占位"));
        LoopAttributes.stashDeferredCalls(ctx,
                List.of(new DeferredToolCall("t2", "deploy", Map.of("k", 1))));

        LoopToolRoundEngine.DeferredReplayOutcome outcome =
                engine.replayDeferredIfNeeded(ctx, new LoopAccumulator(), ToolRoundHooks.NO_OP);

        assertNull(outcome.stop());
        assertEquals(1, executed.get());
        assertTrue(LoopAttributes.deferredCalls(ctx).isEmpty());
        LoopMessage last = ctx.getMessages().get(ctx.getMessages().size() - 1);
        assertEquals("{\"replayed\":true}", last.content());
    }

    /**
     * 部分失败：多调用中单个 fatal 跳过、其余成功 → 不中止，发非终态提示。
     */
    @Test
    void shouldContinueOnPartialFatalFailure() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) -> {
            if ("t1".equals(callId)) {
                throw new FatalToolCallException("E_FATAL", "不可自纠", false);
            }
            return ToolCallResult.ok(callId, toolName, "{\"ok\":true}", null);
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(call("t1", "deploy"), call("t2", "deploy")), ToolRoundHooks.NO_OP);

        assertFalse(result.suspend());
        assertEquals(AgentStop.MAX_STEPS, acc.stop);
        assertEquals(1, eventsOf(RuntimeEventType.BUSINESS_ERROR).size());
    }

    /**
     * 多调用全部 fatal 才中止。
     */
    @Test
    void shouldAbortWhenAllCallsFatal() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) -> {
            throw new FatalToolCallException("E_FATAL", "失败:" + callId, false);
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(call("t1", "deploy"), call("t2", "deploy")), ToolRoundHooks.NO_OP);

        assertTrue(result.suspend());
        assertEquals(AgentStop.BUSINESS_ERROR, acc.stop);
        assertEquals("E_FATAL", acc.failedCode);
        assertFalse(acc.failedRetryable);
    }

    /**
     * 单调用 fatal 立即中止。
     */
    @Test
    void shouldAbortOnSingleFatalCall() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) -> {
            throw new FatalToolCallException("E_FATAL", "不可自纠", false);
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(call("t1", "deploy")), ToolRoundHooks.NO_OP);

        assertTrue(result.suspend());
        assertEquals(AgentStop.BUSINESS_ERROR, acc.stop);
        assertEquals("E_FATAL", acc.failedCode);
    }

    /**
     * 参数解析失败安全语义：半截 JSON 参数 → 工具绝不执行（空参放行会把解析失败
     * 伪装成合法无参调用，写类工具误执行），失败回执进消息流让模型自纠。
     */
    @Test
    void shouldRejectTruncatedJsonArgsWithoutExecuting() {
        AtomicInteger executed = new AtomicInteger();
        ToolInvoker invoker = (callId, toolName, arguments, ctx) -> {
            executed.incrementAndGet();
            return ToolCallResult.ok(callId, toolName, "{\"ok\":true}", null);
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(new LoopToolCall("t1", "deploy", "{\"region\":\"华东")), ToolRoundHooks.NO_OP);

        // 工具未执行；轮次不挂起（失败回执反馈模型自纠，可重试语义）但标记轮内工具失败
        assertEquals(0, executed.get());
        assertFalse(result.suspend());
        assertTrue(result.roundHadToolFailure());
        LoopMessage receipt = ctx.getMessages().get(ctx.getMessages().size() - 1);
        assertEquals(LoopMessage.ROLE_TOOL, receipt.role());
        assertEquals("t1", receipt.toolCallId());
        assertTrue(receipt.content().contains("不是合法 JSON"),
                "失败回执应提示参数非法: " + receipt.content());
    }

    /**
     * 澄清挂起：发 CLARIFICATION 事件并挂起循环。
     */
    @Test
    void shouldSuspendOnClarification() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) ->
                ToolCallResult.ok(callId, toolName, "{\"ok\":true}", null);
        ToolCallInspector inspector = new ToolCallInspector() {
            @Override
            public ApprovalRequest inspectApproval(ToolCallResult result) {
                return null;
            }

            @Override
            public ClarificationRequest inspectClarification(ToolCallResult result) {
                return new ClarificationRequest("要哪个装备?", List.of("甲", "乙"));
            }
        };
        LoopToolRoundEngine engine = new LoopToolRoundEngine(invoker, inspector, MAPPER);
        AgentRunContext ctx = newContext();
        LoopAccumulator acc = new LoopAccumulator();

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(ctx, acc,
                List.of(call("t1", "search")), ToolRoundHooks.NO_OP);

        assertTrue(result.suspend());
        assertEquals(AgentStop.NEEDS_CLARIFICATION, acc.stop);
        assertEquals(1, eventsOf(RuntimeEventType.CLARIFICATION).size());
    }
}
