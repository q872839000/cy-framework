// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.observe;


import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Micrometer 观测埋点单元测试。
 *
 * @author pcy
 * @since 2026/08/27
 */
class MicrometerRuntimeObserverTest {

    private SimpleMeterRegistry registry;
    private MicrometerRuntimeObserver observer;

    /**
     * 每个用例使用独立的内存指标注册表。
     */
    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        observer = new MicrometerRuntimeObserver(registry);
    }

    /**
     * 运行开始事件计入 orrery.agent.runs.started。
     */
    @Test
    void shouldCountRunStarted() {
        observer.accept(event(RuntimeEventType.RUN_STARTED, Map.of()));
        observer.accept(event(RuntimeEventType.RUN_STARTED, Map.of()));

        assertEquals(2.0, registry.get("orrery.agent.runs.started").counter().count());
    }

    /**
     * 运行完成事件计入完成计数，并按 data 遥测记录轮次 / token 分布。
     */
    @Test
    void shouldCountRunCompletedAndRecordTelemetry() {
        observer.accept(event(RuntimeEventType.RUN_COMPLETED, Map.of(
                "stop", "FINAL_ANSWER",
                "rounds", 5,
                "toolCalls", 7,
                "promptTokens", 1200L,
                "completionTokens", 300L)));

        assertEquals(1.0, registry.get("orrery.agent.runs.completed").counter().count());
        assertEquals(1L, registry.get("orrery.agent.run.rounds").summary().count());
        assertEquals(5.0, registry.get("orrery.agent.run.rounds").summary().totalAmount());
        assertEquals(7.0, registry.get("orrery.agent.run.tool.calls").summary().totalAmount());
        assertEquals(1200.0, registry.get("orrery.agent.run.tokens")
                .tags("kind", "prompt").summary().totalAmount());
        assertEquals(300.0, registry.get("orrery.agent.run.tokens")
                .tags("kind", "completion").summary().totalAmount());
    }

    /**
     * 遥测键缺失时只计完成数，不记录分布摘要。
     */
    @Test
    void shouldSkipTelemetrySummaryWhenDataMissing() {
        observer.accept(event(RuntimeEventType.RUN_COMPLETED, Map.of()));

        assertEquals(1.0, registry.get("orrery.agent.runs.completed").counter().count());
        assertThrows(io.micrometer.core.instrument.search.MeterNotFoundException.class,
                () -> registry.get("orrery.agent.run.rounds").summary());
    }

    /**
     * 运行失败事件计入 orrery.agent.runs.failed 并携带 code tag。
     */
    @Test
    void shouldCountRunFailedWithCodeTag() {
        observer.accept(event(RuntimeEventType.RUN_FAILED, Map.of(
                "code", "AGENT_RUN_FAILED", "message", "boom", "retryable", false)));

        assertEquals(1.0, registry.get("orrery.agent.runs.failed")
                .tags("code", "AGENT_RUN_FAILED").counter().count());
    }

    /**
     * 运行取消事件计入 orrery.agent.runs.cancelled。
     */
    @Test
    void shouldCountCancelled() {
        observer.accept(event(RuntimeEventType.CANCELLED, Map.of("reason", "用户取消", "phase", "loop")));

        assertEquals(1.0, registry.get("orrery.agent.runs.cancelled").counter().count());
    }

    /**
     * 工具成功调用：timer 记录耗时，调用计数携带 tool / success tag。
     */
    @Test
    void shouldRecordToolDurationAndCallsOnSuccess() {
        observer.accept(event(RuntimeEventType.TOOL_FINISHED, Map.of(
                "toolCallId", "c1", "toolName", "exec", "durationMs", 120L, "success", true)));

        assertEquals(1L, registry.get("orrery.agent.tool.duration")
                .tags("tool", "exec", "success", "true").timer().count());
        assertEquals(120.0, registry.get("orrery.agent.tool.duration")
                .tags("tool", "exec", "success", "true").timer().totalTime(TimeUnit.MILLISECONDS));
        assertEquals(1.0, registry.get("orrery.agent.tool.calls")
                .tags("tool", "exec", "success", "true").counter().count());
    }

    /**
     * 工具失败调用：调用计数附加 error tag。
     */
    @Test
    void shouldTagErrorOnToolFailure() {
        observer.accept(event(RuntimeEventType.TOOL_FINISHED, Map.of(
                "toolCallId", "c2", "toolName", "exec", "durationMs", 30L,
                "success", false, "errorCode", "TIMEOUT")));

        assertEquals(1.0, registry.get("orrery.agent.tool.calls")
                .tags("tool", "exec", "success", "false", "error", "TIMEOUT").counter().count());
    }

    /**
     * 熔断类系统追踪按 kind 计数，上下文压缩单独计数。
     */
    @Test
    void shouldCountFusesAndCompactions() {
        observer.accept(event(RuntimeEventType.SYSTEM_TRACE,
                Map.of("kind", "agent.idle_fuse", "message", "m")));
        observer.accept(event(RuntimeEventType.SYSTEM_TRACE,
                Map.of("kind", "pipeline.rollbacks_fuse", "message", "m")));
        observer.accept(event(RuntimeEventType.SYSTEM_TRACE,
                Map.of("kind", "context.compacted", "message", "m")));
        observer.accept(event(RuntimeEventType.SYSTEM_TRACE,
                Map.of("kind", "stage.switched", "message", "m")));

        assertEquals(1.0, registry.get("orrery.agent.fuses")
                .tags("kind", "agent.idle_fuse").counter().count());
        assertEquals(1.0, registry.get("orrery.agent.fuses")
                .tags("kind", "pipeline.rollbacks_fuse").counter().count());
        assertEquals(1.0, registry.get("orrery.agent.context.compactions").counter().count());
        // 非白名单 kind 不产生任何熔断指标
        assertEquals(2L, registry.find("orrery.agent.fuses").meters().size());
    }

    /**
     * 审批与澄清事件分别计数。
     */
    @Test
    void shouldCountApprovalsAndClarifications() {
        observer.accept(event(RuntimeEventType.APPROVAL_REQUIRED, Map.of(
                "approvalId", "a1", "summary", "s", "callId", "c1", "affectedKeys", List.of())));
        observer.accept(event(RuntimeEventType.CLARIFICATION, Map.of(
                "question", "q", "options", List.of())));

        assertEquals(1.0, registry.get("orrery.agent.approvals.required").counter().count());
        assertEquals(1.0, registry.get("orrery.agent.clarifications").counter().count());
    }

    /**
     * data 值类型不符（如 rounds 给字符串）时不抛异常、不记录该数值，后续事件仍正常处理。
     */
    @Test
    void shouldTolerateIllTypedDataValues() {
        assertDoesNotThrow(() -> observer.accept(event(RuntimeEventType.RUN_COMPLETED, Map.of(
                "rounds", "not-a-number", "promptTokens", 100L))));

        assertEquals(1.0, registry.get("orrery.agent.runs.completed").counter().count());
        assertThrows(io.micrometer.core.instrument.search.MeterNotFoundException.class,
                () -> registry.get("orrery.agent.run.rounds").summary());
        assertEquals(100.0, registry.get("orrery.agent.run.tokens")
                .tags("kind", "prompt").summary().totalAmount());
    }

    /**
     * 构造一条运行时事件。
     *
     * @param type 事件类型
     * @param data 事件数据
     * @return 运行时事件
     */
    private static RuntimeEvent event(RuntimeEventType type, Map<String, Object> data) {
        return new RuntimeEvent(1L, type, "run-1", data, type.terminal(), System.currentTimeMillis());
    }
}
