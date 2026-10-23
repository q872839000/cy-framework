// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.protocol;


import com.cy.orrery.agent.runtime.event.Events;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.protocol.event.AgentEvent;
import com.cy.orrery.protocol.event.BlockType;
import com.cy.orrery.protocol.event.Channel;
import com.cy.orrery.protocol.event.EventPayload;
import com.cy.orrery.protocol.event.EventType;
import com.cy.orrery.protocol.event.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentEventBridge 单元测试（线协议映射 / 块生命周期 / 终态语义 / 故障隔离）。
 *
 * @author pcy
 * @since 2026/08/27
 */
class AgentEventBridgeTest {

    /** 收集型下发端口。 */
    private static final class CollectingPublisher implements AgentEventPublisher {

        private final List<AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }
    }

    /**
     * 基础事件映射：payload 类型正确、信封公共字段（seq / runId / agentId / traceId）透传。
     */
    @Test
    void shouldMapRuntimeEventsToProtocolPayloads() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        CollectingPublisher publisher = new CollectingPublisher();
        bus.addListener(new AgentEventBridge(publisher, "scenario-agent"));

        RuntimeEvent started = Events.runStarted(bus);
        Events.stepStarted(bus, 1);
        Events.toolStarted(bus, "c1", "search");
        Events.toolFinished(bus, "c1", "search", 120L, false, "TOOL_TIMEOUT");
        Events.milestone(bus, "计划完成");
        Events.systemTrace(bus, "context.compacted", "上下文已压缩", Map.of("before", 100, "after", 40));
        Events.clarification(bus, "请补充目标区域", List.of("A", "B"));
        Events.approvalRequired(bus, "ap-1", "审批摘要", "c1", List.of("k1", "k2"));
        Events.businessError(bus, "BIZ_X", "业务提示", true);

        List<AgentEvent> events = publisher.events;
        assertEquals(9, events.size());
        // seq 契约：桥是协议发射端，per-run 计数器逐事件取新序——
        // 严格递增互不相同，且不低于对应源事件的运行时 seq（初值 = 首个源事件 seq + 1）
        long previousSeq = 0L;
        for (int i = 0; i < events.size(); i++) {
            long seq = events.get(i).seq();
            assertTrue(seq > previousSeq, "协议 seq 必须严格递增互不相同: index=" + i);
            assertTrue(seq > i + 1L, "协议 seq 不得低于运行时侧 seq: index=" + i);
            previousSeq = seq;
        }

        // RUN_STARTED → SystemTrace（调试频道）
        AgentEvent first = events.get(0);
        assertEquals(EventType.SYSTEM_TRACE, first.type());
        assertEquals(Channel.DEBUG, first.channel());
        EventPayload.SystemTrace startTrace = assertInstanceOf(
                EventPayload.SystemTrace.class, first.data());
        assertEquals("agent.start", startTrace.category());
        assertEquals("运行开始", startTrace.message());
        assertNull(startTrace.meta());
        // 信封公共字段
        assertEquals("run-1", first.runId());
        assertEquals("scenario-agent", first.agentId());
        assertEquals("run-1", first.traceId());
        assertNull(first.spanId());
        assertEquals(started.timestampMillis(), first.timestampMs());
        assertFalse(first.terminal());

        // STEP_STARTED
        EventPayload.StepStarted stepStarted = assertInstanceOf(
                EventPayload.StepStarted.class, events.get(1).data());
        assertEquals(1, stepStarted.stepIndex());
        assertEquals(Channel.USER, events.get(1).channel());

        // TOOL_STARTED
        EventPayload.ToolCallStart toolStart = assertInstanceOf(
                EventPayload.ToolCallStart.class, events.get(2).data());
        assertEquals("c1", toolStart.callId());
        assertEquals("search", toolStart.tool());
        assertNull(toolStart.arguments());
        assertNull(toolStart.narration());

        // TOOL_FINISHED
        EventPayload.ToolCallEnd toolEnd = assertInstanceOf(
                EventPayload.ToolCallEnd.class, events.get(3).data());
        assertEquals("c1", toolEnd.callId());
        assertEquals("search", toolEnd.tool());
        assertFalse(toolEnd.success());
        assertEquals(120L, toolEnd.durationMs());
        assertEquals("TOOL_TIMEOUT", toolEnd.error());

        // MILESTONE
        EventPayload.Milestone milestone = assertInstanceOf(
                EventPayload.Milestone.class, events.get(4).data());
        assertEquals("general", milestone.kind());
        assertEquals("计划完成", milestone.title());
        assertNull(milestone.payload());

        // SYSTEM_TRACE（extras 进 meta，剔除 kind / message）
        EventPayload.SystemTrace trace = assertInstanceOf(
                EventPayload.SystemTrace.class, events.get(5).data());
        assertEquals("context.compacted", trace.category());
        assertEquals("上下文已压缩", trace.message());
        Map<?, ?> meta = assertInstanceOf(Map.class, trace.meta());
        assertEquals(100, meta.get("before"));
        assertEquals(40, meta.get("after"));
        assertEquals(2, meta.size());

        // CLARIFICATION
        EventPayload.Clarification clarification = assertInstanceOf(
                EventPayload.Clarification.class, events.get(6).data());
        assertEquals("请补充目标区域", clarification.question());
        assertEquals(List.of("A", "B"), clarification.suggestions());
        assertEquals(EventPayload.Clarification.KIND_INFO, clarification.kind());

        // APPROVAL_REQUIRED
        EventPayload.ApprovalRequired approval = assertInstanceOf(
                EventPayload.ApprovalRequired.class, events.get(7).data());
        assertEquals("ap-1", approval.approvalId());
        assertEquals("审批摘要", approval.summary());
        Map<?, ?> diff = assertInstanceOf(Map.class, approval.diff());
        assertEquals("c1", diff.get("callId"));
        assertEquals(List.of("k1", "k2"), diff.get("affectedKeys"));

        // BUSINESS_ERROR
        EventPayload.BusinessError businessError = assertInstanceOf(
                EventPayload.BusinessError.class, events.get(8).data());
        assertEquals("BIZ_X", businessError.code());
        assertEquals("业务提示", businessError.message());
        assertTrue(businessError.retryable());
    }

    /**
     * 流式块生命周期：首 delta 前补 BlockStart，STEP_FINISHED 前闭合未闭合块；
     * 正文相位按 hadToolCalls 结算（COMMENTARY / FINAL）。
     */
    @Test
    void shouldManageTextDeltaBlockLifecycle() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        CollectingPublisher publisher = new CollectingPublisher();
        bus.addListener(new AgentEventBridge(publisher, "agent"));

        Events.stepStarted(bus, 1);
        Events.textDelta(bus, 1, "你好", "text");
        Events.textDelta(bus, 1, "世界", "text");
        Events.textDelta(bus, 1, "思考中", "reasoning");
        Events.stepFinished(bus, 1, false);

        List<AgentEvent> events = publisher.events;
        assertEquals(9, events.size());

        // seq 契约：一条 TEXT_DELTA 合成的 BlockStart 与 BlockDelta 各自取新 seq，
        // 严格递增互不相同；closeBlocks 闭合事件与 StepFinished 同理
        long previousSeq = 0L;
        for (AgentEvent event : events) {
            assertTrue(event.seq() > previousSeq, "协议 seq 必须严格递增互不相同");
            previousSeq = event.seq();
        }
        // 首条 TEXT_DELTA 的合成对：BlockStart.seq < BlockDelta.seq
        assertTrue(events.get(1).seq() < events.get(2).seq());
        // STEP_FINISHED 前置闭合：两个 BlockEnd 按开启顺序递增，且先于 StepFinished
        assertTrue(events.get(6).seq() < events.get(7).seq());
        assertTrue(events.get(7).seq() < events.get(8).seq());

        assertEquals(EventType.STEP_STARTED, events.get(0).type());

        EventPayload.BlockStart textStart = assertInstanceOf(
                EventPayload.BlockStart.class, events.get(1).data());
        assertEquals(1, textStart.stepIndex());
        assertEquals("s1-text", textStart.blockId());
        assertEquals(BlockType.TEXT, textStart.blockType());
        assertNull(textStart.label());

        EventPayload.BlockDelta firstDelta = assertInstanceOf(
                EventPayload.BlockDelta.class, events.get(2).data());
        assertEquals("s1-text", firstDelta.blockId());
        assertEquals("你好", firstDelta.delta());
        assertEquals(BlockType.TEXT, firstDelta.blockType());
        assertEquals("世界", ((EventPayload.BlockDelta) events.get(3).data()).delta());

        EventPayload.BlockStart reasoningStart = assertInstanceOf(
                EventPayload.BlockStart.class, events.get(4).data());
        assertEquals("s1-reasoning", reasoningStart.blockId());
        assertEquals(BlockType.REASONING, reasoningStart.blockType());
        assertEquals("思考中", ((EventPayload.BlockDelta) events.get(5).data()).delta());

        // STEP_FINISHED 前按块开启顺序闭合
        EventPayload.BlockEnd textEnd = assertInstanceOf(
                EventPayload.BlockEnd.class, events.get(6).data());
        assertEquals("s1-text", textEnd.blockId());
        assertEquals(BlockType.TEXT, textEnd.blockType());
        EventPayload.BlockEnd reasoningEnd = assertInstanceOf(
                EventPayload.BlockEnd.class, events.get(7).data());
        assertEquals("s1-reasoning", reasoningEnd.blockId());

        // 无工具调用收尾 → FINAL 相位
        EventPayload.StepFinished stepFinished = assertInstanceOf(
                EventPayload.StepFinished.class, events.get(8).data());
        assertEquals(1, stepFinished.stepIndex());
        assertFalse(stepFinished.hadToolCalls());
        assertEquals(Phase.FINAL, stepFinished.terminalPhase());

        // 带工具调用收尾 → COMMENTARY 相位
        publisher.events.clear();
        Events.stepStarted(bus, 2);
        Events.textDelta(bus, 2, "过渡", "text");
        Events.stepFinished(bus, 2, true);

        List<AgentEvent> commentary = publisher.events;
        assertEquals(5, commentary.size());
        assertEquals(EventType.BLOCK_END, commentary.get(3).type());
        // 闭合块同样各自取新 seq：BlockEnd 先于 StepFinished 且严格递增
        assertTrue(commentary.get(3).seq() < commentary.get(4).seq());
        EventPayload.StepFinished commentaryFinish = assertInstanceOf(
                EventPayload.StepFinished.class, commentary.get(4).data());
        assertTrue(commentaryFinish.hadToolCalls());
        assertEquals(Phase.COMMENTARY, commentaryFinish.terminalPhase());
    }

    /**
     * 终态事件映射：Final / RunFailed / Cancelled 均为 terminal=true。
     */
    @Test
    void shouldMarkTerminalEvents() {
        InMemoryRuntimeEventBus bus = new InMemoryRuntimeEventBus("run-1");
        CollectingPublisher publisher = new CollectingPublisher();
        bus.addListener(new AgentEventBridge(publisher, "agent"));

        Events.runCompleted(bus, Map.of("rounds", 3, "toolCalls", 2,
                "promptTokens", 100, "completionTokens", 50));
        Events.runFailed(bus, "LLM_CALL_FAILED", "模型失败", true);
        Events.cancelled(bus, "用户取消", "executing");

        List<AgentEvent> events = publisher.events;
        assertEquals(3, events.size());

        AgentEvent completed = events.get(0);
        assertEquals(EventType.FINAL, completed.type());
        assertTrue(completed.terminal());
        EventPayload.Final finalPayload = assertInstanceOf(EventPayload.Final.class, completed.data());
        assertEquals("COMPLETED", finalPayload.result().stop());
        assertEquals(3, finalPayload.result().rounds());
        assertEquals(2, finalPayload.result().toolCalls());
        assertNull(finalPayload.result().resumeToken());
        assertNull(finalPayload.result().ext());

        AgentEvent failed = events.get(1);
        assertEquals(EventType.RUN_FAILED, failed.type());
        assertTrue(failed.terminal());
        EventPayload.RunFailed runFailed = assertInstanceOf(
                EventPayload.RunFailed.class, failed.data());
        assertEquals("LLM_CALL_FAILED", runFailed.code());
        assertEquals("模型失败", runFailed.message());
        assertTrue(runFailed.retryable());
        assertEquals("LLM_CALL_FAILED", runFailed.result().stop());
        assertEquals(0, runFailed.result().rounds());

        AgentEvent cancelled = events.get(2);
        assertEquals(EventType.CANCELLED, cancelled.type());
        assertTrue(cancelled.terminal());
        EventPayload.Cancelled cancelledPayload = assertInstanceOf(
                EventPayload.Cancelled.class, cancelled.data());
        assertEquals("用户取消", cancelledPayload.reason());
        assertEquals("executing", cancelledPayload.phase());
        assertNull(cancelledPayload.resumeToken());
    }

    /**
     * 前向兼容兜底：未覆盖的运行时类型落 DomainData（kind=runtime.类型名小写，ext 为原始 data）。
     * <p>
     * 当前 RuntimeEventType 全部被显式分支覆盖，default 分支不可达；
     * 这里直接验证兜底映射函数本身（新增枚举常量时即走该路径）。
     * </p>
     */
    @Test
    void shouldFallbackUnknownTypeToDomainData() {
        RuntimeEvent event = new RuntimeEvent(99L, RuntimeEventType.RUN_STARTED, "run-1",
                Map.of("k", "v"), false, 123L);

        EventPayload.DomainData domainData = AgentEventBridge.toDomainData(event);

        assertEquals("runtime.run_started", domainData.kind());
        assertEquals(Map.of("k", "v"), domainData.ext());
        assertEquals(EventType.DOMAIN_DATA, domainData.type());
    }

    /**
     * 发布故障隔离：publisher 抛异常不向引擎传播，后续事件照常下发。
     */
    @Test
    void shouldIsolatePublisherFailure() {
        AtomicInteger calls = new AtomicInteger();
        List<AgentEvent> received = new ArrayList<>();
        AgentEventPublisher flaky = event -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            received.add(event);
        };
        AgentEventBridge bridge = new AgentEventBridge(flaky, "agent");

        RuntimeEvent first = new RuntimeEvent(1L, RuntimeEventType.RUN_STARTED, "run-1",
                Map.of(), false, 0L);
        RuntimeEvent second = new RuntimeEvent(2L, RuntimeEventType.MILESTONE, "run-1",
                Map.of("title", "t"), false, 0L);

        assertDoesNotThrow(() -> bridge.accept(first));
        bridge.accept(second);

        assertEquals(2, calls.get());
        assertEquals(1, received.size());
        assertEquals(EventType.MILESTONE, received.getFirst().type());
    }
}
