// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm.impl;


import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.llm.LlmCallOptions;
import com.cy.orrery.agent.runtime.llm.LlmInvoker;
import com.cy.orrery.agent.runtime.llm.LlmMessageOps;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 截断自动续接装饰器：输出达到 max_tokens 截断时自动发起续接拼接。
 * <p>
 * 续接编排（对应宿主截断续接的通用形态）：截断结果 → 临时挂载半截 assistant
 * 消息 + 续接指令 → 再调 LLM → 拼接正文 / 合并 tool_calls → 撤掉临时消息，
 * 以完整结果返回（引擎只挂载最终完整消息）。有界续接（默认 2 轮）；
 * 续接失败或超限返回 failed=true 的失败结果（错误码
 * TRUNCATION_CONTINUATION_EXCEEDED）——半截结果不可信，不再原样放行给引擎
 * 当作正常轮执行（引擎侧对截断语义同样不执行半截 tool_calls，装饰器与引擎
 * 双侧一致）。用户介入续写路径由宿主 HITL 承担，本装饰器不越界。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class TruncationContinuationDecorator implements LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(TruncationContinuationDecorator.class);

    private final LlmInvoker delegate;
    private final int maxContinuations;

    /**
     * 构造续接装饰器（默认最多 2 轮续接）。
     *
     * @param delegate 被包装的 LLM 调用 SPI
     */
    public TruncationContinuationDecorator(LlmInvoker delegate) {
        this(delegate, 2);
    }

    /**
     * 构造续接装饰器。
     *
     * @param delegate         被包装的 LLM 调用 SPI
     * @param maxContinuations 最大续接轮数（≥1）
     */
    public TruncationContinuationDecorator(LlmInvoker delegate, int maxContinuations) {
        this.delegate = delegate;
        this.maxContinuations = Math.max(1, maxContinuations);
    }

    @Override
    public List<Map<String, Object>> toolSpecs() {
        return delegate.toolSpecs();
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
        return invoke(ctx, roundIndex, toolSpecs, LlmCallOptions.NONE);
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex,
                                  List<Map<String, Object>> toolSpecs, LlmCallOptions options) {
        // options 透传：截断续接保持原调用的结构化输出约束（response_format/tool_choice）
        LlmRoundResult first = delegate.invoke(ctx, roundIndex, toolSpecs, options);
        if (first.stopHint() != AgentStop.OUTPUT_TRUNCATED) {
            return first;
        }
        return continueTruncated(ctx, roundIndex, toolSpecs, options, first);
    }

    /**
     * 续接拼接主循环。
     *
     * @param ctx        运行上下文
     * @param roundIndex 轮次下标
     * @param toolSpecs  工具规格
     * @param options    调用选项（续接调用保持同一约束）
     * @param first      截断的首次结果
     * @return 拼接后的完整结果；放弃续接时原样返回截断结果
     */
    private LlmRoundResult continueTruncated(AgentRunContext ctx, int roundIndex,
                                              List<Map<String, Object>> toolSpecs,
                                              LlmCallOptions options,
                                              LlmRoundResult first) {
        StringBuilder content = new StringBuilder(first.content() == null ? "" : first.content());
        StringBuilder reasoning = new StringBuilder(first.reasoning() == null ? "" : first.reasoning());
        int promptTokens = first.promptTokens();
        int completionTokens = first.completionTokens();
        for (int attempt = 1; attempt <= maxContinuations; attempt++) {
            LlmRoundResult next = invokeWithContinuationContext(ctx, roundIndex, toolSpecs, options,
                    content.toString(), attempt);
            promptTokens += next.promptTokens();
            completionTokens += next.completionTokens();
            if (next.content() != null && !next.content().isBlank()) {
                content.append(next.content());
            }
            if (next.reasoning() != null && !next.reasoning().isBlank()) {
                reasoning.append(next.reasoning());
            }
            if (next.stopHint() == AgentStop.OUTPUT_TRUNCATED) {
                // 续接仍截断：保留已拼接内容，进入下一轮续接
                log.info("续接仍截断，继续拼接 runId={} round={} attempt={}", ctx.getRunId(), roundIndex, attempt);
                continue;
            }
            if (next.failed()) {
                log.warn("截断续接失败 runId={} round={} attempt={}", ctx.getRunId(), roundIndex, attempt);
                return continuationAborted("续接调用失败：" + next.errorMessage());
            }
            // 续接正常结束：带 tool_calls 则合并返回，否则纯正文拼接返回
            List<LoopToolCall> calls =
                    next.toolCalls() == null ? List.of() : next.toolCalls();
            return LlmRoundResult.ok(content.toString(),
                    reasoning.isEmpty() ? null : reasoning.toString(),
                    calls, promptTokens, completionTokens);
        }
        log.warn("截断续接超限，放弃 runId={} round={}", ctx.getRunId(), roundIndex);
        return continuationAborted("续接 " + maxContinuations + " 轮后仍被截断");
    }

    /**
     * 续接放弃的失败结果（failed=true，错误码 TRUNCATION_CONTINUATION_EXCEEDED）。
     * <p>
     * 半截拼接结果不可信（可能中断在半个 JSON / 半个参数里），绝不以 ok 语义放行；
     * stopHint 保持 OUTPUT_TRUNCATED，引擎按截断终态收敛。
     * </p>
     *
     * @param detail 放弃原因（日志级细节）
     * @return 失败的轮结果
     */
    private LlmRoundResult continuationAborted(String detail) {
        RuntimeErrorCode code = RuntimeErrorCode.TRUNCATION_CONTINUATION_EXCEEDED;
        return LlmRoundResult.fail(code.code(), code.format() + "（" + detail + "）",
                AgentStop.OUTPUT_TRUNCATED, code.retryable());
    }

    /**
     * 挂临时续接上下文（半截 assistant + 续接指令）调用，调用后撤掉临时消息。
     *
     * @param ctx        运行上下文
     * @param roundIndex 轮次下标
     * @param toolSpecs  工具规格
     * @param options    调用选项（续接保持原约束）
     * @param contentSoFar 已拼接正文
     * @param attempt    续接序号
     * @return 续接调用结果
     */
    private LlmRoundResult invokeWithContinuationContext(AgentRunContext ctx, int roundIndex,
                                                          List<Map<String, Object>> toolSpecs,
                                                          LlmCallOptions options,
                                                          String contentSoFar, int attempt) {
        ctx.addMessage(LoopMessage.assistant(contentSoFar, null, null));
        ctx.addMessage(LoopMessage.user("继续，从中断处接着输出，不要重复已输出内容。"));
        try {
            return delegate.invoke(ctx, roundIndex, toolSpecs, options);
        } finally {
            LlmMessageOps.removeLastMessages(ctx, 2);
        }
    }
}
