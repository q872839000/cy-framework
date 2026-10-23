// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读工具并行执行测试：并发证明、保序挂载、审批屏障语义不变。
 *
 * @author pcy
 * @since 2026/08/27
 */
class LoopToolRoundEngineParallelTest {

    /** 记录并发水位与执行顺序的工具集（只读工具经闩门证明真并行）。 */
    static final class ConcurrentTools implements ToolInvoker {

        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final CountDownLatch bothReadOnlyInside = new CountDownLatch(2);
        final CountDownLatch releaseReadOnly = new CountDownLatch(1);
        final List<String> executed = new CopyOnWriteArrayList<>();

        @Override
        public ToolCallResult invoke(String callId, String toolName, Map<String, Object> arguments,
                                     AgentRunContext ctx) {
            executed.add(toolName);
            if (isReadOnly(toolName)) {
                bothReadOnlyInside.countDown();
                int now = active.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try {
                    releaseReadOnly.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                }
            }
            return ToolCallResult.ok(callId, toolName, "{\"ok\":true}", toolName + " done");
        }

        @Override
        public boolean isReadOnly(String toolName) {
            return toolName.startsWith("ro");
        }
    }

    private AgentRunContext newContext(String runId) {
        return new AgentRunContext(runId, new InMemoryRuntimeEventBus(runId), null, List.of());
    }

    @Test
    void readOnlyCallsRunInParallelAndMountInOriginalOrder() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ConcurrentTools tools = new ConcurrentTools();
            LoopToolRoundEngine engine = new LoopToolRoundEngine(
                    tools, ToolCallInspector.NoOp.INSTANCE, new ObjectMapper(), executor);
            AgentRunContext ctx = newContext("run-p1");

            // 主线程执行轮次会被只读调用阻塞（闩门），由后台线程确认两个只读都入场后放行
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    tools.bothReadOnlyInside.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                tools.releaseReadOnly.countDown();
            });

            List<LoopToolCall> calls = List.of(
                    new LoopToolCall("c1", "roQueryA", "{}"),
                    new LoopToolCall("c2", "roQueryB", "{}"),
                    new LoopToolCall("c3", "writeOp", "{}"));
            LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(
                    ctx, new LoopAccumulator(), calls, ToolRoundHooks.NO_OP);

            assertTrue(!result.suspend());
            assertEquals(2, tools.maxConcurrent.get(), "两个只读调用必须真并行");
            assertEquals(new java.util.HashSet<>(List.of("roQueryA", "roQueryB", "writeOp")),
                    new java.util.HashSet<>(tools.executed), "三个调用都必须恰好执行一次");

            // tool 消息挂载顺序 == LLM 请求的原始调用顺序（协议配对不乱）
            List<String> receiptIds = ctx.getMessages().stream()
                    .filter(m -> LoopMessage.ROLE_TOOL.equals(m.role()))
                    .map(LoopMessage::toolCallId).toList();
            assertEquals(List.of("c1", "c2", "c3"), receiptIds);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void approvalBarrierStillDefersEvenWhenReadOnlyPreExecuted() {
        ConcurrentTools tools = new ConcurrentTools();
        tools.bothReadOnlyInside.countDown();
        tools.bothReadOnlyInside.countDown();
        tools.releaseReadOnly.countDown();
        LoopToolRoundEngine engine = new LoopToolRoundEngine(
                tools, new ToolCallInspector() {
                    @Override
                    public ApprovalRequest inspectApproval(ToolCallResult result) {
                        return "writeOp".equals(result.toolName())
                                ? new ApprovalRequest("ap-1", "写操作审批", result.callId(), null)
                                : null;
                    }

                    @Override
                    public ClarificationRequest inspectClarification(ToolCallResult result) {
                        return null;
                    }
                }, new ObjectMapper(),
                Executors.newVirtualThreadPerTaskExecutor());
        AgentRunContext ctx = newContext("run-p2");

        List<LoopToolCall> calls = List.of(
                new LoopToolCall("c1", "roQueryA", "{}"),
                new LoopToolCall("c2", "writeOp", "{}"),
                new LoopToolCall("c3", "roQueryB", "{}"));
        LoopAccumulator acc = new LoopAccumulator();
        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(
                ctx, acc, calls, ToolRoundHooks.NO_OP);

        assertTrue(result.suspend());
        assertEquals(AgentStop.NEEDS_APPROVAL, acc.stop);
        assertEquals(1, LoopAttributes.deferredCalls(ctx).size());
        assertEquals("c3", LoopAttributes.deferredCalls(ctx).getFirst().callId(),
                "屏障后的只读调用即使已预执行也必须按暂缓处理");
        assertEquals("ap-1", LoopAttributes.pendingApprovals(ctx).getFirst().approvalId(),
                "待审批请求必须随属性暂存（供运行门面挂起持久化）");
        // c3 的回执是占位回执（协议配对），不是真实执行结果
        String c3Receipt = ctx.getMessages().stream()
                .filter(m -> LoopMessage.ROLE_TOOL.equals(m.role()) && "c3".equals(m.toolCallId()))
                .map(LoopMessage::content).findFirst().orElseThrow();
        assertTrue(c3Receipt.contains("deferred"));
    }

    @Test
    void serialEngineKeepsLegacyBehaviorWithoutExecutor() {
        ConcurrentTools tools = new ConcurrentTools();
        tools.releaseReadOnly.countDown();
        LoopToolRoundEngine engine = new LoopToolRoundEngine(
                tools, ToolCallInspector.NoOp.INSTANCE, new ObjectMapper());
        AgentRunContext ctx = newContext("run-p3");

        LoopToolRoundEngine.ToolRoundResult result = engine.executeToolCallsRound(
                ctx, new LoopAccumulator(), List.of(
                        new LoopToolCall("c1", "roQueryA", "{}"),
                        new LoopToolCall("c2", "roQueryB", "{}")),
                ToolRoundHooks.NO_OP);

        assertTrue(!result.suspend());
        assertEquals(1, tools.maxConcurrent.get(), "无执行器时必须保持串行");
    }
}
