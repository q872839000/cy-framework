// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.llm.impl.RetryingLlmInvoker;
import com.cy.orrery.agent.runtime.llm.impl.TruncationContinuationDecorator;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import com.cy.orrery.agent.runtime.llm.LlmInvoker.LlmRoundResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 装饰器单元测试（有界重试 / 截断续接拼接）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class LlmDecoratorTest {

    /** 脚本化 LLM：按序列返回结果，耗尽后返回成功文本。 */
    private static final class ScriptedLlm implements LlmInvoker {

        private final Deque<LlmRoundResult> script = new ArrayDeque<>();
        int invocations;

        void enqueue(LlmRoundResult result) {
            script.addLast(result);
        }

        @Override
        public List<Map<String, Object>> toolSpecs() {
            return List.of();
        }

        @Override
        public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex,
                                     List<Map<String, Object>> toolSpecs) {
            invocations++;
            LlmRoundResult next = script.pollFirst();
            return next == null
                    ? LlmRoundResult.ok("done", null, List.of(), 10, 5)
                    : next;
        }
    }

    private AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    /**
     * 瞬时失败重试：第 1 次失败、第 2 次成功 → 重试后成功。
     */
    @Test
    void shouldRetryTransientFailure() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("LLM_CALL_FAILED", "连接超时", null));
        RetryingLlmInvoker retrying = new RetryingLlmInvoker(scripted,
                new LlmRetryPolicy(2, 1));

        LlmRoundResult result = retrying.invoke(newContext(), 0, List.of());

        assertTrue(!result.failed());
        assertEquals(2, scripted.invocations);
    }

    /**
     * 确定性失败不重试：截断语义直接透传。
     */
    @Test
    void shouldNotRetryDeterministicFailure() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断", AgentStop.OUTPUT_TRUNCATED));
        RetryingLlmInvoker retrying = new RetryingLlmInvoker(scripted,
                new LlmRetryPolicy(2, 1));

        LlmRoundResult result = retrying.invoke(newContext(), 0, List.of());

        assertTrue(result.failed());
        assertEquals(1, scripted.invocations);
    }

    /**
     * 取消响应：重试前检测到取消信号即抛取消异常，不再发起重试。
     */
    @Test
    void shouldAbortRetryWhenCancelled() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("LLM_CALL_FAILED", "连接超时", null));
        RetryingLlmInvoker retrying = new RetryingLlmInvoker(scripted,
                new LlmRetryPolicy(2, 1));
        AgentRunContext ctx = newContext();
        ctx.getBus().cancel("user request");

        assertThrows(RunCancellationException.class, () -> retrying.invoke(ctx, 0, List.of()));
        assertEquals(1, scripted.invocations);
    }

    /**
     * 截断自动续接：首次截断 → 续接返回完整 tool_calls → 拼接成功且撤掉临时消息。
     */
    @Test
    void shouldContinueTruncatedOutput() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断", AgentStop.OUTPUT_TRUNCATED));
        scripted.enqueue(LlmRoundResult.ok("后半段", null,
                List.of(new LoopToolCall("c1", "search", "{}")), 10, 5));
        TruncationContinuationDecorator decorator =
                new TruncationContinuationDecorator(scripted);
        AgentRunContext ctx = newContext();

        LlmRoundResult result = decorator.invoke(ctx, 0, List.of());

        assertTrue(!result.failed());
        assertTrue(result.content().contains("后半段"));
        assertEquals(1, result.toolCalls().size());
        // 临时续接消息已撤掉
        assertTrue(ctx.getMessages().isEmpty());
    }

    /**
     * 续接超限放弃：返回 failed=true 的失败结果（TRUNCATION_CONTINUATION_EXCEEDED）——
     * 半截拼接不可信，绝不以 ok 语义放行给引擎。
     */
    @Test
    void shouldGiveUpAfterMaxContinuations() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断1", AgentStop.OUTPUT_TRUNCATED));
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断2", AgentStop.OUTPUT_TRUNCATED));
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断3", AgentStop.OUTPUT_TRUNCATED));
        TruncationContinuationDecorator decorator =
                new TruncationContinuationDecorator(scripted, 2);

        LlmRoundResult result = decorator.invoke(newContext(), 0, List.of());

        assertTrue(result.failed());
        assertEquals("TRUNCATION_CONTINUATION_EXCEEDED", result.errorCode());
        assertEquals(AgentStop.OUTPUT_TRUNCATED, result.stopHint());
        assertEquals(3, scripted.invocations);
    }

    /**
     * 续接调用失败（非截断语义的传输失败）：同样以失败结果收口，不把半截结果
     * 当正常轮放行；临时续接消息撤除。
     */
    @Test
    void shouldFailContinuationWithoutPassingThrough() {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("OUT", "截断", AgentStop.OUTPUT_TRUNCATED));
        scripted.enqueue(LlmRoundResult.fail("LLM_CALL_FAILED", "断流", null));
        TruncationContinuationDecorator decorator =
                new TruncationContinuationDecorator(scripted);
        AgentRunContext ctx = newContext();

        LlmRoundResult result = decorator.invoke(ctx, 0, List.of());

        assertTrue(result.failed());
        assertEquals("TRUNCATION_CONTINUATION_EXCEEDED", result.errorCode());
        assertEquals(AgentStop.OUTPUT_TRUNCATED, result.stopHint());
        assertEquals(2, scripted.invocations);
        assertTrue(ctx.getMessages().isEmpty());
    }

    /**
     * 重试退避被中断（运行取消的典型路径）：恢复中断标记、立即停止重试，
     * 按取消失败返回（LLM_CANCELLED），不再发起下一跳。
     */
    @Test
    void shouldStopRetryAndRestoreInterruptWhenBackoffInterrupted() throws Exception {
        ScriptedLlm scripted = new ScriptedLlm();
        scripted.enqueue(LlmRoundResult.fail("LLM_CALL_FAILED", "连接超时", null));
        RetryingLlmInvoker retrying = new RetryingLlmInvoker(scripted,
                new LlmRetryPolicy(2, 10_000));
        Thread testThread = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                // 测试辅助线程：睡眠被打断不影响主流程
            }
            testThread.interrupt();
        });
        interrupter.start();
        try {
            LlmRoundResult result = retrying.invoke(newContext(), 0, List.of());

            assertTrue(result.failed());
            assertEquals("LLM_CANCELLED", result.errorCode());
            assertEquals(1, scripted.invocations);
            // 中断标记已恢复（interrupted() 读取并清除，避免污染测试线程后续用例）
            assertTrue(Thread.interrupted(), "退避中断后必须恢复线程中断标记");
        } finally {
            interrupter.join();
        }
    }
}
