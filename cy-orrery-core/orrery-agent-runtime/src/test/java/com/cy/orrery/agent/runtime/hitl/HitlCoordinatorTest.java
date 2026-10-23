// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalGate;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.DeferredToolCall;
import com.cy.orrery.agent.runtime.loop.LoopAttributes;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HITL 协调器单元测试（挂起-等待-注入 / 超时兜底 / 拒绝清空暂缓）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class HitlCoordinatorTest {

    private AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    /**
     * 批准：合成回执覆盖占位回执，暂缓调用保留待引擎重放。
     */
    @Test
    void shouldApplyApprovalAndKeepDeferred() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        HitlCoordinator coordinator = new HitlCoordinator(gate);
        AgentRunContext ctx = newContext();
        ctx.addMessage(LoopMessage.tool("c1", "占位"));
        LoopAttributes.stashDeferredCalls(ctx,
                List.of(new DeferredToolCall("c2", "deploy", Map.of())));

        ApprovalRequest approval = new ApprovalRequest("apv-1", "高危操作", "c1", List.of());
        coordinator.suspend(ctx, List.of(approval), null);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> gate.decide("apv-1", true, "admin", null));
            List<ApprovalDecision> decisions =
                    coordinator.awaitAndApply(ctx, 3000, ToolRoundHooks.NO_OP);

            assertEquals(1, decisions.size());
            assertTrue(decisions.get(0).approved());
            assertFalse(LoopAttributes.deferredCalls(ctx).isEmpty());
            assertEquals("c2", LoopAttributes.deferredCalls(ctx).get(0).callId());
            assertTrue(ctx.getMessages().get(0).content().contains("approved"));
            assertEquals(null, HitlAttributes.suspendedState(ctx));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 拒绝：拒绝回执 + 暂缓调用清空。
     */
    @Test
    void shouldApplyRejectionAndClearDeferred() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        HitlCoordinator coordinator = new HitlCoordinator(gate);
        AgentRunContext ctx = newContext();
        LoopAttributes.stashDeferredCalls(ctx,
                List.of(new DeferredToolCall("c2", "deploy", Map.of())));

        ApprovalRequest approval = new ApprovalRequest("apv-1", "高危操作", "c1", List.of());
        coordinator.suspend(ctx, List.of(approval), null);
        gate.decide("apv-1", false, "admin", "不允许");

        List<ApprovalDecision> decisions =
                coordinator.awaitAndApply(ctx, 1000, ToolRoundHooks.NO_OP);

        assertFalse(decisions.get(0).approved());
        assertTrue(LoopAttributes.deferredCalls(ctx).isEmpty());
    }

    /**
     * 超时：按拒绝兜底。
     */
    @Test
    void shouldFallbackToRejectionOnTimeout() throws Exception {
        HitlCoordinator coordinator = new HitlCoordinator(new InMemoryApprovalGate());
        AgentRunContext ctx = newContext();
        ApprovalRequest approval = new ApprovalRequest("apv-1", "高危操作", "c1", List.of());
        coordinator.suspend(ctx, List.of(approval), null);

        List<ApprovalDecision> decisions =
                coordinator.awaitAndApply(ctx, 100, ToolRoundHooks.NO_OP);

        assertFalse(decisions.get(0).approved());
        assertTrue(decisions.get(0).timedOut());
    }

    /**
     * 挂起态契约：runId / 原因 / 待审批 / 宿主扩展位。
     */
    @Test
    void shouldBuildSuspendedStateContract() {
        HitlCoordinator coordinator = new HitlCoordinator(new InMemoryApprovalGate());
        AgentRunContext ctx = newContext();
        ApprovalRequest approval = new ApprovalRequest("apv-1", "高危操作", "c1", List.of("obj-1"));

        SuspendedState state = coordinator.suspend(ctx, List.of(approval), Map.of("intent", "deploy"));

        assertEquals("run-1", state.runId());
        assertEquals(SuspensionReason.NEEDS_APPROVAL, state.reason());
        assertEquals(1, state.pendingApprovals().size());
        assertEquals("deploy", state.hostExtras().get("intent"));
        assertEquals(state, HitlAttributes.suspendedState(ctx));
    }

    /**
     * 等待期间取消：逐单检查取消信号，命中即中止本单及剩余审批单（abort 释放
     * 等待者）并抛 RunCancellationException，由运行器收敛 CANCELLED 终态。
     */
    @Test
    void shouldAbortRemainingApprovalsWhenCancelledDuringAwait() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        HitlCoordinator coordinator = new HitlCoordinator(gate);
        AgentRunContext ctx = newContext();
        coordinator.suspend(ctx, List.of(
                new ApprovalRequest("apv-1", "高危操作一", "c1", List.of()),
                new ApprovalRequest("apv-2", "高危操作二", "c2", List.of())), null);
        ctx.getBus().cancel("用户取消");

        RunCancellationException ex = assertThrows(RunCancellationException.class,
                () -> coordinator.awaitAndApply(ctx, 1000, ToolRoundHooks.NO_OP));

        assertEquals("hitl_await_apv-1", ex.phase());
        // 两单均已中止：注册表条目清空，迟到决定不再接受
        assertEquals(0, gate.pendingCount());
        assertFalse(gate.decide("apv-1", true, "admin", null));
        assertFalse(gate.decide("apv-2", true, "admin", null));
    }

    /**
     * 审批门泄漏防护：决定先于等待到达可正常取回，等待取走后注册表条目清理。
     */
    @Test
    void shouldCleanupGateEntryAfterDecisionAndAwait() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        gate.register("apv-1");
        gate.decide("apv-1", true, "admin", null);
        assertEquals(1, gate.pendingCount());

        ApprovalDecision decision = gate.awaitDecision("apv-1", 1000);

        assertTrue(decision.approved());
        assertEquals(0, gate.pendingCount());
    }

    /**
     * 审批门泄漏防护：等待超时放弃后注册表条目即清理（迟到决定不再接受）。
     */
    @Test
    void shouldCleanupGateEntryAfterTimeout() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        gate.register("apv-1");

        ApprovalDecision decision = gate.awaitDecision("apv-1", 50);

        assertTrue(decision.timedOut());
        assertEquals(0, gate.pendingCount());
        assertFalse(gate.decide("apv-1", true, "admin", null));
    }
}
