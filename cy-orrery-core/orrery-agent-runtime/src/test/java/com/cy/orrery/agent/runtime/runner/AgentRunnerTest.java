// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.runner;


import com.cy.orrery.agent.runtime.checkpoint.Checkpoint;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointManager;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.Durability;
import com.cy.orrery.agent.runtime.checkpoint.impl.InMemoryCheckpointStore;
import com.cy.orrery.agent.runtime.checkpoint.impl.JacksonCheckpointSerializer;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.RuntimeEventBusFactory;
import com.cy.orrery.agent.runtime.event.RuntimeEventType;
import com.cy.orrery.agent.runtime.hitl.HitlAttributes;
import com.cy.orrery.agent.runtime.hitl.HitlCoordinator;
import com.cy.orrery.agent.runtime.hitl.impl.InMemoryApprovalGate;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.ClarificationRequest;
import com.cy.orrery.agent.runtime.loop.LoopAccumulator;
import com.cy.orrery.agent.runtime.loop.LoopAttributes;
import com.cy.orrery.agent.runtime.loop.LoopConfig;
import com.cy.orrery.agent.runtime.loop.LoopDomainSpec;
import com.cy.orrery.agent.runtime.loop.LoopOutcome;
import com.cy.orrery.agent.runtime.loop.LoopRuntimeSpec;
import com.cy.orrery.agent.runtime.loop.LoopToolRoundEngine;
import com.cy.orrery.agent.runtime.loop.ReactLoopEngine;
import com.cy.orrery.agent.runtime.loop.ToolCallInspector;
import com.cy.orrery.agent.runtime.loop.ToolCallResult;
import com.cy.orrery.agent.runtime.loop.ToolInvoker;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentRunner 端到端测试：工具触发审批 → 挂起落盘 → 从检查点恢复（经 JSON 序列化
 * 往返）→ 审批批准 → 确定性重放暂缓调用 → 循环收敛 → 终态清理。
 *
 * @author pcy
 * @since 2026/08/27
 */
class AgentRunnerTest {

    /** 经 JSON 序列化往返的检查点存储（验证持久化真实语义，而非对象引用共享）。 */
    static final class SerializingCheckpointStore implements CheckpointStore {

        private final InMemoryCheckpointStore delegate = new InMemoryCheckpointStore();
        private final JacksonCheckpointSerializer serializer =
                new JacksonCheckpointSerializer(new ObjectMapper());

        @Override
        public Optional<com.cy.orrery.agent.runtime.checkpoint.Checkpoint> load(String runId) {
            return delegate.load(runId)
                    .map(cp -> serializer.deserialize(serializer.serialize(cp)));
        }

        @Override
        public void save(com.cy.orrery.agent.runtime.checkpoint.Checkpoint checkpoint) {
            delegate.save(serializer.deserialize(serializer.serialize(checkpoint)));
        }

        @Override
        public void delete(String runId) {
            delegate.delete(runId);
        }
    }

    /** 脚本化 LLM。 */
    static final class FakeLlm implements LlmInvoker {

        private final Deque<LlmRoundResult> script = new ArrayDeque<>();

        void enqueue(LlmRoundResult result) {
            script.addLast(result);
        }

        @Override
        public List<Map<String, Object>> toolSpecs() {
            return List.of();
        }

        @Override
        public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
            LlmRoundResult next = script.pollFirst();
            return next == null ? LlmRoundResult.ok("全部完成。", null, List.of(), 10, 5) : next;
        }
    }

    /** 记录执行顺序的工具集。 */
    static final class RecordingTools implements ToolInvoker {

        final List<String> executed = new CopyOnWriteArrayList<>();

        @Override
        public ToolCallResult invoke(String callId, String toolName, Map<String, Object> arguments,
                                     AgentRunContext ctx) {
            executed.add(toolName);
            return ToolCallResult.ok(callId, toolName, "{\"ok\":true}", toolName + " 已完成");
        }
    }

    /** writeOp 触发审批的检查器。 */
    static final class WriteApprovalInspector implements ToolCallInspector {

        @Override
        public ApprovalRequest inspectApproval(ToolCallResult result) {
            return "writeOp".equals(result.toolName())
                    ? new ApprovalRequest("ap-1", "写操作需审批", result.callId(), null)
                    : null;
        }

        @Override
        public ClarificationRequest inspectClarification(ToolCallResult result) {
            return null;
        }
    }

    /** 无工具轮即终局的领域钩子。 */
    static final class FinalOnNoToolCall implements LoopDomainSpec {

        @Override
        public boolean handleNoToolCallRound(AgentRunContext ctx, LoopAccumulator acc,
                                             LlmInvoker.LlmRoundResult round) {
            acc.stop = AgentStop.FINAL_ANSWER;
            acc.finalText = round.content();
            return true;
        }
    }

    @Test
    void approvalSuspendCheckpointResumeReplayAndFinish() throws Exception {
        SerializingCheckpointStore store = new SerializingCheckpointStore();
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
        RuntimeEventBusFactory busFactory = new RuntimeEventBusFactory();
        busFactory.addGlobalListener(events::add);
        AgentRunner runner = new AgentRunner(busFactory, new CheckpointManager(store),
                new HitlCoordinator(gate), null, Durability.EXIT);

        RecordingTools tools = new RecordingTools();
        ReactLoopEngine engine = new ReactLoopEngine(new LoopToolRoundEngine(
                tools, new WriteApprovalInspector(), new ObjectMapper()));
        FakeLlm llm = new FakeLlm();
        LoopRuntimeSpec spec = LoopRuntimeSpec.of(LoopConfig.defaults(), llm,
                new FinalOnNoToolCall(), null, ToolRoundHooks.NO_OP);

        // 第一轮：LLM 请求 [writeOp, readOp]；writeOp 触发审批 → readOp 暂缓
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("", null, List.of(
                new LoopToolCall("c1", "writeOp", "{}"),
                new LoopToolCall("c2", "readOp", "{}")), 10, 5));

        AgentRunContext ctx = runner.startContext("run-1", List.of(LoopMessage.user("执行操作")));
        LoopOutcome first = runner.runReact(engine, ctx, spec);

        assertEquals(AgentStop.NEEDS_APPROVAL, first.stop());
        assertEquals(List.of("writeOp"), tools.executed);
        assertTrue(store.load("run-1").isPresent(), "挂起后检查点必须存在");

        // 模拟进程重启：丢掉原上下文，从检查点恢复（经 JSON 往返）
        AgentRunContext restored = runner.restoreContext("run-1").orElseThrow();
        assertEquals(4, restored.getMessages().size(), "user + assistant + 两个 tool 回执");
        assertEquals("ap-1", com.cy.orrery.agent.runtime.hitl.HitlAttributes
                .suspendedState(restored).pendingApprovals().getFirst().approvalId());
        assertEquals(1, com.cy.orrery.agent.runtime.loop.LoopAttributes
                .deferredCalls(restored).size(), "暂缓调用必须随检查点恢复");

        // 审批批准后续跑：确定性重放 readOp，下轮无工具 → 终局
        gate.decide("ap-1", true, "tester", null);
        LoopOutcome second = runner.awaitApprovalsAndResume(engine, restored, spec, 1000);

        assertEquals(AgentStop.FINAL_ANSWER, second.stop());
        assertEquals(List.of("writeOp", "readOp"), tools.executed,
                "批准后暂缓调用必须按原参数确定性重放一次且仅一次");
        assertTrue(store.load("run-1").isEmpty(), "终态后检查点必须清理");

        List<RuntimeEventType> types = events.stream().map(RuntimeEvent::type).toList();
        assertEquals(RuntimeEventType.RUN_STARTED, types.getFirst());
        assertTrue(types.contains(RuntimeEventType.APPROVAL_REQUIRED));
        assertEquals(RuntimeEventType.RUN_COMPLETED, types.getLast());
    }

    @Test
    void rejectionClearsDeferredCalls() throws Exception {
        SerializingCheckpointStore store = new SerializingCheckpointStore();
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        RuntimeEventBusFactory busFactory = new RuntimeEventBusFactory();
        AgentRunner runner = new AgentRunner(busFactory, new CheckpointManager(store),
                new HitlCoordinator(gate), null, Durability.EXIT);

        RecordingTools tools = new RecordingTools();
        ReactLoopEngine engine = new ReactLoopEngine(new LoopToolRoundEngine(
                tools, new WriteApprovalInspector(), new ObjectMapper()));
        FakeLlm llm = new FakeLlm();
        LoopRuntimeSpec spec = LoopRuntimeSpec.of(LoopConfig.defaults(), llm,
                new FinalOnNoToolCall(), null, ToolRoundHooks.NO_OP);

        llm.enqueue(LlmInvoker.LlmRoundResult.ok("", null, List.of(
                new LoopToolCall("c1", "writeOp", "{}"),
                new LoopToolCall("c2", "readOp", "{}")), 10, 5));

        AgentRunContext ctx = runner.startContext("run-2", new ArrayList<>());
        runner.runReact(engine, ctx, spec);

        AgentRunContext restored = runner.restoreContext("run-2").orElseThrow();
        gate.decide("ap-1", false, "tester", "不允许");
        LoopOutcome outcome = runner.awaitApprovalsAndResume(engine, restored, spec, 1000);

        assertEquals(AgentStop.FINAL_ANSWER, outcome.stop());
        assertEquals(List.of("writeOp"), tools.executed, "拒绝后暂缓调用不得重放");
        assertTrue(com.cy.orrery.agent.runtime.loop.LoopAttributes
                .deferredCalls(restored).isEmpty());
    }
}
