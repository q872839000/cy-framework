// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 循环引擎单元测试（双熔断 / 终局结算 / 取消）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class ReactLoopEngineTest {

    private static final LoopToolCall TOOL_CALL = new LoopToolCall("c1", "search", "{}");

    private AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    private LoopToolRoundEngine failingToolEngine() {
        ToolInvoker invoker = (callId, toolName, arguments, ctx) ->
                ToolCallResult.fail(callId, toolName, "E_FAIL", "失败", null);
        return new LoopToolRoundEngine(invoker, ToolCallInspector.NoOp.INSTANCE,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /**
     * 正常完成：工具轮后模型给出终答，领域终止。
     */
    @Test
    void shouldCompleteWithFinalAnswer() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("先查一下", null,
                List.of(TOOL_CALL), 10, 5));
        LoopTestSupport.FakeLlmInvoker tailLlm = new LoopTestSupport.FakeLlmInvoker();
        LoopTestSupport.RecordingDomainSpec domain = new LoopTestSupport.RecordingDomainSpec();
        domain.terminateOnNoToolCall = true;
        ToolInvoker okInvoker = (callId, toolName, arguments, ctx) ->
                ToolCallResult.ok(callId, toolName, "{\"ok\":true}", "已查询");
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine(okInvoker, ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        // 第 1 轮工具调用；第 2 轮纯文本（脚本耗尽后默认无工具）
        LoopOutcome outcome = engine.run(newContext(), LoopRuntimeSpec.of(
                LoopConfig.of(5, 3, 3), llm, domain, null, null));

        assertEquals(AgentStop.FINAL_ANSWER, outcome.stop());
        assertEquals(2, outcome.rounds());
        assertEquals(1, outcome.toolCallCount());
    }

    /**
     * 收敛熔断：连续零事实进展轮触发终局结算。
     */
    @Test
    void shouldTriggerIdleFuse() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        LoopTestSupport.RecordingDomainSpec domain = new LoopTestSupport.RecordingDomainSpec();
        domain.signature = "sig-fixed";
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine((c, n, a, x) ->
                        ToolCallResult.ok(c, n, "{}", null),
                        ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        LoopOutcome outcome = engine.run(newContext(), LoopRuntimeSpec.of(
                LoopConfig.of(10, 3, 2), llm, domain, null, null));

        // 第 1 轮签名与 null 不同视为进展；第 2 轮起签名不变累计；达 2 触发熔断
        assertEquals(1, domain.settledReasons.size());
        assertEquals(LoopDomainSpec.TerminalReason.IDLE_FUSE,
                domain.settledReasons.get(0));
        assertEquals(3, outcome.rounds());
    }

    /**
     * 退化熔断：连续工具失败零业务进展轮硬停。
     */
    @Test
    void shouldTriggerDegradationFuse() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("查", null, List.of(TOOL_CALL), 5, 2));
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("再查", null, List.of(TOOL_CALL), 5, 2));
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("又查", null, List.of(TOOL_CALL), 5, 2));
        LoopTestSupport.RecordingDomainSpec domain = new LoopTestSupport.RecordingDomainSpec();
        ReactLoopEngine engine = new ReactLoopEngine(failingToolEngine());

        LoopOutcome outcome = engine.run(newContext(), LoopRuntimeSpec.of(
                LoopConfig.of(10, 3, 3), llm, domain, null, null));

        assertEquals(AgentStop.BUSINESS_ERROR, outcome.stop());
        assertEquals("REPEATED_TOOL_FAILURE", outcome.failedCode());
        assertTrue(!outcome.failedRetryable());
    }

    /**
     * 取消检查点：取消后抛出取消异常。
     */
    @Test
    void shouldThrowOnCancellation() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        AgentRunContext ctx = newContext();
        ctx.getBus().cancel("user interrupted");
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine((c, n, a, x) -> ToolCallResult.ok(c, n, "{}", null),
                        ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        assertThrows(RunCancellationException.class, () -> engine.run(ctx,
                LoopRuntimeSpec.of(LoopConfig.of(5, 3, 3), llm,
                        new LoopTestSupport.RecordingDomainSpec(), null, null)));
    }

    /**
     * 截断终态：OUTPUT_TRUNCATED 到达引擎（无续接装饰器 / 续接失败）时，
     * 半截 tool_calls JSON 不可信——全部丢弃不执行；已下发的半截正文保留在
     * 终态结果里，按截断错误终态收敛。
     */
    @Test
    void shouldTerminateOnTruncatedOutputAndDropToolCalls() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        // 模拟 AiFacadeLlmInvoker 的截断结果：非 failed（errorCode=null）+ stopHint，
        // 带半截 tool_calls（截断在参数 JSON 中间，执行即事故）
        llm.enqueue(new LlmInvoker.LlmRoundResult("半截正文", null,
                List.of(TOOL_CALL), 10, 5, null, null, AgentStop.OUTPUT_TRUNCATED, true));
        java.util.concurrent.atomic.AtomicInteger executed = new java.util.concurrent.atomic.AtomicInteger();
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine((c, n, a, x) -> {
                    executed.incrementAndGet();
                    return ToolCallResult.ok(c, n, "{}", null);
                }, ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        LoopOutcome outcome = engine.run(newContext(), LoopRuntimeSpec.of(
                LoopConfig.of(5, 3, 3), llm, new LoopTestSupport.RecordingDomainSpec(), null, null));

        assertEquals(AgentStop.OUTPUT_TRUNCATED, outcome.stop());
        assertEquals("AGENT_OUTPUT_TRUNCATED", outcome.failedCode());
        assertEquals("半截正文", outcome.finalText());
        assertEquals(0, executed.get());
        assertEquals(0, outcome.toolCallCount());
    }

    /**
     * 轮次耗尽：走终局结算路径。
     */
    @Test
    void shouldSettleOnRoundsExhausted() {
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("查", null, List.of(TOOL_CALL), 5, 2));
        llm.enqueue(LvmRoundResultHelper.withToolCalls());
        LoopTestSupport.RecordingDomainSpec domain = new LoopTestSupport.RecordingDomainSpec();
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine((c, n, a, x) -> ToolCallResult.ok(c, n, "{}", null),
                        ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        LoopOutcome outcome = engine.run(newContext(), LoopRuntimeSpec.of(
                LoopConfig.of(2, 3, 3), llm, domain, null, null));

        assertEquals(LoopDomainSpec.TerminalReason.ROUNDS_EXHAUSTED,
                domain.settledReasons.get(0));
        assertEquals(AgentStop.MAX_STEPS, outcome.stop());
    }

    /** 测试助手：带工具调用的轮结果。 */
    private static final class LvmRoundResultHelper {

        private static LlmInvoker.LlmRoundResult withToolCalls() {
            return LlmInvoker.LlmRoundResult.ok("查", null, List.of(TOOL_CALL), 5, 2);
        }
    }
}
