// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * loop 引擎测试支撑：脚本化 LLM 与领域钩子（包私有）。
 *
 * @author pcy
 * @since 2026/08/19
 */
final class LoopTestSupport {

    private LoopTestSupport() {
    }

    /**
     * 脚本化 LLM：按预设序列返回轮结果，耗尽后返回无工具纯文本。
     */
    static final class FakeLlmInvoker implements LlmInvoker {

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
            return next == null
                    ? LlmRoundResult.ok("done", null, List.of(), 10, 5)
                    : next;
        }
    }

    /**
     * 记录型领域钩子：可配置终局行为、状态签名与终局结算记录。
     */
    static final class RecordingDomainSpec implements LoopDomainSpec {

        final List<TerminalReason> settledReasons = new ArrayList<>();
        boolean terminateOnNoToolCall;
        String signature;

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
        }

        @Override
        public String stateSignature(AgentRunContext ctx) {
            return signature;
        }
    }
}
