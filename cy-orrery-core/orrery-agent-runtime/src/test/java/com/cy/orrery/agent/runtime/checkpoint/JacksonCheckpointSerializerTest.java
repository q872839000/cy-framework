// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;


import com.cy.orrery.agent.runtime.checkpoint.impl.InMemoryCheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.impl.JacksonCheckpointSerializer;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.hitl.SuspendedState;
import com.cy.orrery.agent.runtime.hitl.SuspensionReason;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.DeferredToolCall;
import com.cy.orrery.agent.runtime.plan.LedgerItemStatus;
import com.cy.orrery.agent.runtime.plan.PlanLedger;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jackson 检查点序列化往返测试：内核已知键强类型还原 + 未注册键通用还原。
 *
 * @author pcy
 * @since 2026/08/27
 */
class JacksonCheckpointSerializerTest {

    private final JacksonCheckpointSerializer serializer =
            new JacksonCheckpointSerializer(new ObjectMapper());

    @Test
    void roundTripRestoresKernelAttributeTypes() {
        AgentRunContext ctx = new AgentRunContext("run-1",
                new com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus("run-1"),
                null, List.of(LoopMessage.user("部署两架无人机")));
        ctx.addMessage(LoopMessage.assistant("", null,
                List.of(new LoopToolCall("c1", "deploy", "{\"n\":2}"))));
        ctx.addMessage(LoopMessage.tool("c1", "{\"ok\":true}"));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("loop.actionLog", List.class),
                new java.util.ArrayList<>(List.of("已部署无人机 x1")));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("loop.deferredCalls", List.class),
                new java.util.ArrayList<>(List.of(new DeferredToolCall("c2", "scout", Map.of("area", "A")))));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("loop.pendingApprovals", List.class),
                new java.util.ArrayList<>(List.of(new ApprovalRequest("ap-1", "变更审批", "c1", List.of("u1")))));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("hitl.suspendedState", SuspendedState.class),
                SuspendedState.ofApproval("run-1",
                        List.of(new ApprovalRequest("ap-1", "变更审批", "c1", List.of("u1"))), null));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("plan.ledger", PlanLedger.class),
                PlanLedger.of(List.of(PlanLedger.LedgerItem.pending("L1", "部署侦察", "正在部署侦察"))));
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("pipeline.transitions", Integer.class), 3);
        ctx.putAttr(new com.cy.orrery.agent.runtime.context.AttributeKey<>("custom.obj", Object.class),
                Map.of("note", "宿主自定义"));
        ctx.restoreStepCursor(7);
        ctx.setCompactionBoundary(2);

        Checkpoint checkpoint = Checkpoint.of(ctx, 5);
        Checkpoint restored = serializer.deserialize(serializer.serialize(checkpoint));

        assertEquals("run-1", restored.runId());
        assertEquals(5, restored.revision());
        assertEquals(7, restored.stepCursor());
        assertEquals(2, restored.compactionBoundary());
        assertEquals(3, restored.messages().size(), "user + assistant + tool 回执");
        assertEquals(LoopMessage.ROLE_TOOL, restored.messages().get(2).role());
        assertEquals("c1", restored.messages().get(2).toolCallId());
        assertEquals("deploy", restored.messages().get(1).toolCalls().getFirst().name());

        Map<String, Object> attrs = restored.attributes();
        @SuppressWarnings("unchecked")
        List<String> actionLog = (List<String>) attrs.get("loop.actionLog");
        assertEquals(List.of("已部署无人机 x1"), actionLog);

        @SuppressWarnings("unchecked")
        List<DeferredToolCall> deferred = (List<DeferredToolCall>) attrs.get("loop.deferredCalls");
        assertInstanceOf(DeferredToolCall.class, deferred.getFirst());
        assertEquals("scout", deferred.getFirst().toolName());
        assertEquals("A", deferred.getFirst().arguments().get("area"));

        @SuppressWarnings("unchecked")
        List<ApprovalRequest> pending = (List<ApprovalRequest>) attrs.get("loop.pendingApprovals");
        assertInstanceOf(ApprovalRequest.class, pending.getFirst());
        assertEquals("ap-1", pending.getFirst().approvalId());

        SuspendedState state = (SuspendedState) attrs.get("hitl.suspendedState");
        assertNotNull(state);
        assertEquals(SuspensionReason.NEEDS_APPROVAL, state.reason());
        assertEquals("ap-1", state.pendingApprovals().getFirst().approvalId());

        PlanLedger ledger = (PlanLedger) attrs.get("plan.ledger");
        assertNotNull(ledger);
        assertEquals("L1", ledger.items().getFirst().id());
        assertEquals("正在部署侦察", ledger.items().getFirst().activeForm());
        assertEquals("正在部署侦察", ledger.items().getFirst().displayText());

        assertEquals(3, attrs.get("pipeline.transitions"));

        // 未注册键：还原为通用 Map（宿主自行后处理）
        @SuppressWarnings("unchecked")
        Map<String, Object> custom = (Map<String, Object>) attrs.get("custom.obj");
        assertEquals("宿主自定义", custom.get("note"));
    }

    @Test
    void ledgerDeserializesOldFormatWithoutActiveForm() throws Exception {
        // 旧检查点的账本条目没有 activeForm 字段：反序列化应容忍缺失并回退 null
        String legacyJson = """
                {"items":[{"id":"L1","description":"部署侦察","status":"PENDING"},
                          {"id":"L2","description":"汇总","status":"DONE"}],"revision":3}
                """;
        PlanLedger legacy = new ObjectMapper().readValue(legacyJson, PlanLedger.class);
        assertEquals(2, legacy.items().size());
        assertNull(legacy.items().getFirst().activeForm());
        assertEquals("部署侦察", legacy.items().getFirst().displayText());
        assertEquals(LedgerItemStatus.DONE, legacy.items().get(1).status());
        assertEquals(3, legacy.revision());
    }

    @Test
    void managerRestoreRebuildsContext() {
        AgentRunContext ctx = new AgentRunContext("run-2",
                new com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus("run-2"),
                null, List.of(LoopMessage.user("hi")));
        ctx.restoreStepCursor(3);
        CheckpointManager manager = new CheckpointManager(new InMemoryCheckpointStore());
        manager.save(ctx);

        AgentRunContext restored = manager.restore("run-2",
                new com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus("run-2"), null).orElseThrow();
        assertEquals("run-2", restored.getRunId());
        assertEquals(1, restored.getMessages().size());
        assertEquals(3, restored.currentStepIndex());
        assertEquals(4, restored.nextStepIndex());

        // revision 递增
        Checkpoint second = manager.save(restored);
        assertTrue(second.revision() >= 2);
    }
}
