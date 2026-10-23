// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.plan.LedgerItemStatus;
import com.cy.orrery.agent.runtime.plan.LedgerProgressMatcher;
import com.cy.orrery.agent.runtime.plan.PlanLedger;
import com.cy.orrery.agent.runtime.plan.PlanLedgerAttributes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReAct 引擎的任务账本集成测试（勾稽推进 + 全完成提前收敛）。
 *
 * @author pcy
 * @since 2026/08/19
 */
class ReactLoopEngineLedgerTest {

    private static final LoopToolCall TOOL_CALL = new LoopToolCall("c1", "deploy", "{}");

    /** 匹配器：动作叙述包含条目描述即完成。 */
    private static final LedgerProgressMatcher MATCHER = (item, action) ->
            action != null && action.contains(item.description())
                    ? LedgerProgressMatcher.MatchResult.COMPLETED
                    : LedgerProgressMatcher.MatchResult.NO_MATCH;

    /**
     * 工具轮后勾稽推进：动作完成账本条目，全完成且领域允许时提前收敛。
     */
    @Test
    void shouldReconcileAndSettleEarlyOnLedgerComplete() {
        AgentRunContext ctx = new AgentRunContext("run-1",
                new InMemoryRuntimeEventBus("run-1"), null, null);
        PlanLedgerAttributes.stash(ctx, PlanLedger.of(List.of(
                PlanLedger.LedgerItem.pending("L1", "部署红方飞机"))));

        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("开始", null,
                List.of(TOOL_CALL), 10, 5));
        // 第二轮脚本：即便模型还想继续，账本全完成应提前收敛（不再消耗该轮）
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("继续", null,
                List.of(TOOL_CALL), 10, 5));

        // 领域钩子：动作收集（叙述进 actionLog）+ 匹配器 + 允许提前收敛 + 终局置 FINAL_ANSWER
        LedgerDomainSpec domain = new LedgerDomainSpec();
        ToolRoundHooks hooks = new ToolRoundHooks() {
            @Override
            public void onToolCallSucceeded(ToolCallResult result, Map<String, Object> arguments,
                                            List<String> actionLog) {
                if (result.userMessage() != null) {
                    actionLog.add(result.userMessage());
                }
            }
        };
        // 工具叙述与账本条目匹配
        ToolInvoker invoker = (callId, toolName, arguments, ctx2) ->
                ToolCallResult.ok(callId, toolName, "{}", "已部署红方飞机 3 架");
        LoopToolRoundEngine toolEngine = new LoopToolRoundEngine(invoker,
                ToolCallInspector.NoOp.INSTANCE, new com.fasterxml.jackson.databind.ObjectMapper());
        ReactLoopEngine engine = new ReactLoopEngine(toolEngine);

        LoopOutcome outcome = engine.run(ctx, LoopRuntimeSpec.of(
                LoopConfig.of(10, 3, 3), llm, domain, null, hooks));

        // 第 1 轮工具执行 → 勾稽 L1 完成 → allSettled → 提前收敛（第 2 轮脚本未消耗）
        assertEquals(AgentStop.FINAL_ANSWER, outcome.stop());
        assertEquals(1, outcome.rounds());
        assertEquals(1, outcome.toolCallCount());
        assertEquals(1, domain.settledReasons.size());
        assertEquals(LoopDomainSpec.TerminalReason.LEDGER_COMPLETED,
                domain.settledReasons.get(0));
        // 账本推进后暂存回属性（revision=1，条目 DONE）
        PlanLedger ledger = PlanLedgerAttributes.ledger(ctx);
        assertEquals(1, ledger.revision());
        assertEquals(LedgerItemStatus.DONE, ledger.items().get(0).status());
    }

    /**
     * 未装配账本时零开销：行为与无账本模式一致（不触发提前收敛）。
     */
    @Test
    void shouldSkipLedgerWhenAbsent() {
        AgentRunContext ctx = new AgentRunContext("run-1",
                new InMemoryRuntimeEventBus("run-1"), null, null);
        LoopTestSupport.FakeLlmInvoker llm = new LoopTestSupport.FakeLlmInvoker();
        llm.enqueue(LlmInvoker.LlmRoundResult.ok("查", null, List.of(TOOL_CALL), 5, 2));
        LedgerDomainSpec domain = new LedgerDomainSpec();
        domain.terminateOnNoToolCall = true;
        ToolInvoker invoker = (callId, toolName, arguments, ctx2) ->
                ToolCallResult.ok(callId, toolName, "{}", "已部署");
        ReactLoopEngine engine = new ReactLoopEngine(
                new LoopToolRoundEngine(invoker, ToolCallInspector.NoOp.INSTANCE,
                        new com.fasterxml.jackson.databind.ObjectMapper()));

        LoopOutcome outcome = engine.run(ctx, LoopRuntimeSpec.of(
                LoopConfig.of(5, 3, 3), llm, domain, null, null));

        // 无账本：工具轮正常后由领域终止（无提前收敛路径）
        assertEquals(AgentStop.FINAL_ANSWER, outcome.stop());
        assertTrue(domain.settledReasons.isEmpty());
    }

    /** 带账本能力的领域钩子（独立实现，含终局记录）。 */
    private static final class LedgerDomainSpec implements LoopDomainSpec {

        final List<TerminalReason> settledReasons = new java.util.ArrayList<>();
        boolean terminateOnNoToolCall;

        @Override
        public boolean handleNoToolCallRound(AgentRunContext ctx, LoopAccumulator acc,
                                              LlmInvoker.LlmRoundResult round) {
            if (terminateOnNoToolCall) {
                acc.stop = AgentStop.FINAL_ANSWER;
                return true;
            }
            return false;
        }

        @Override
        public void settleTerminal(AgentRunContext ctx, LoopAccumulator acc, TerminalReason reason) {
            settledReasons.add(reason);
            acc.stop = AgentStop.FINAL_ANSWER;
            acc.finalText = "按账本完成";
        }

        @Override
        public LedgerProgressMatcher ledgerProgressMatcher() {
            return MATCHER;
        }

        @Override
        public boolean allowEarlySettlement(AgentRunContext ctx) {
            return true;
        }
    }
}
