// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段链引擎单元测试。
 *
 * @author pcy
 * @since 2026/08/19
 */
class PipelineEngineTest {

    private final PipelineEngine engine = new PipelineEngine();

    /**
     * 构造单阶段处理器（测试便捷方法）。
     *
     * @param stage    阶段标识
     * @param behavior 处理行为
     * @return 阶段处理器
     */
    private static StageHandler handler(String stage, Function<AgentRunContext, StageOutcome> behavior) {
        return new StageHandler() {
            @Override
            public String stage() {
                return stage;
            }

            @Override
            public StageOutcome handle(AgentRunContext ctx) {
                return behavior.apply(ctx);
            }
        };
    }

    private AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    /**
     * 顺序推进到完成。
     */
    @Test
    void shouldRunSequentialStagesToDone() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.Continue("B")));
        registry.register(handler("B", ctx -> new StageOutcome.Done("finished")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry, PipelineConfig.defaults());

        assertEquals(PipelineOutcome.Status.DONE, outcome.status());
        assertEquals("finished", outcome.message());
        assertEquals(1, outcome.transitions());
        assertEquals(0, outcome.rollbacks());
    }

    /**
     * 阶段处理器缺失时失败。
     */
    @Test
    void shouldFailOnMissingHandler() {
        PipelineOutcome outcome = engine.run(newContext(), "GHOST",
                new StageHandlerRegistry(), PipelineConfig.defaults());
        assertEquals(PipelineOutcome.Status.FAILED, outcome.status());
        assertEquals("PIPELINE_STAGE_MISSING", outcome.code());
        assertFalse(outcome.retryable());
    }

    /**
     * 回退预算耗尽熔断。
     */
    @Test
    void shouldFuseWhenRollbackBudgetExhausted() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.Rollback("A", "retry")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry,
                PipelineConfig.of(50, 2));

        assertEquals(PipelineOutcome.Status.ROLLBACKS_EXCEEDED, outcome.status());
        assertEquals("PIPELINE_ROLLBACKS_EXCEEDED", outcome.code());
        assertTrue(outcome.retryable());
    }

    /**
     * 转移次数超限熔断（乒乓死循环防护）。
     */
    @Test
    void shouldFuseWhenTransitionsExceeded() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.Continue("B")));
        registry.register(handler("B", ctx -> new StageOutcome.Continue("A")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry,
                PipelineConfig.of(5, 5));

        assertEquals(PipelineOutcome.Status.TRANSITIONS_EXCEEDED, outcome.status());
        assertEquals(5, outcome.transitions());
    }

    /**
     * ContinueLoop 空转自旋受转移熔断约束（同阶段无限重入防护）。
     */
    @Test
    void shouldFuseWhenContinueLoopSpins() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.ContinueLoop()));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry,
                PipelineConfig.of(5, 5));

        assertEquals(PipelineOutcome.Status.TRANSITIONS_EXCEEDED, outcome.status());
        assertEquals(5, outcome.transitions());
    }

    /**
     * ContinueLoop 短路后可继续推进：首次短路重入、第二次跳转到下一阶段并完成。
     */
    @Test
    void shouldContinueLoopThenAdvance() {
        AtomicInteger spins = new AtomicInteger();
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> spins.incrementAndGet() == 1
                ? new StageOutcome.ContinueLoop()
                : new StageOutcome.Continue("B")));
        registry.register(handler("B", ctx -> new StageOutcome.Done("ok")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry, PipelineConfig.of(10, 5));

        assertEquals(PipelineOutcome.Status.DONE, outcome.status());
        assertEquals("ok", outcome.message());
        assertEquals(2, outcome.transitions());
    }

    /**
     * 熔断终态清零计数：同上下文重跑时预算重新武装（恢复完整转移额度而非立即再熔断）。
     */
    @Test
    void shouldResetCountersAfterFuseOnRerun() {
        AtomicInteger executions = new AtomicInteger();
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> {
            executions.incrementAndGet();
            return new StageOutcome.ContinueLoop();
        }));
        AgentRunContext ctx = newContext();

        PipelineOutcome first = engine.run(ctx, "A", registry, PipelineConfig.of(3, 5));
        assertEquals(PipelineOutcome.Status.TRANSITIONS_EXCEEDED, first.status());
        int firstRunExecutions = executions.get();
        assertTrue(firstRunExecutions > 0);

        PipelineOutcome second = engine.run(ctx, "A", registry, PipelineConfig.of(3, 5));
        assertEquals(PipelineOutcome.Status.TRANSITIONS_EXCEEDED, second.status());
        // 第二次运行完整执行了 handler（预算重新武装），而非恢复旧计数后立即熔断。
        assertEquals(firstRunExecutions * 2, executions.get());
    }

    /**
     * 引擎切换通道。
     */
    @Test
    void shouldReturnSwitchEngineOutcome() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A",
                ctx -> new StageOutcome.SwitchEngine("react", "{\"feedback\":\"fix\"}", "consistency")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry, PipelineConfig.defaults());

        assertEquals(PipelineOutcome.Status.SWITCH_ENGINE, outcome.status());
        assertEquals("react", outcome.targetEngine());
        assertNotNull(outcome.handoffPayload());
    }

    /**
     * 用户裁定终态携带问题与选项。
     */
    @Test
    void shouldReturnAskUserDecisionOutcome() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A",
                ctx -> new StageOutcome.AskUserDecision("选哪个?", List.of("1. 甲", "2. 乙"))));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry, PipelineConfig.defaults());

        assertEquals(PipelineOutcome.Status.ASK_USER_DECISION, outcome.status());
        assertEquals("选哪个?", outcome.question());
        assertEquals(2, outcome.options().size());
    }

    /**
     * 等待确认终态。
     */
    @Test
    void shouldReturnAwaitingOutcome() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.Awaiting("等待确认", "A")));

        PipelineOutcome outcome = engine.run(newContext(), "A", registry, PipelineConfig.defaults());

        assertEquals(PipelineOutcome.Status.AWAITING, outcome.status());
        assertEquals("等待确认", outcome.message());
    }

    /**
     * 重复注册快速失败。
     */
    @Test
    void shouldRejectDuplicateRegistration() {
        StageHandlerRegistry registry = new StageHandlerRegistry();
        registry.register(handler("A", ctx -> new StageOutcome.ContinueLoop()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> registry.register(handler("A", ctx -> new StageOutcome.ContinueLoop())));
    }
}
