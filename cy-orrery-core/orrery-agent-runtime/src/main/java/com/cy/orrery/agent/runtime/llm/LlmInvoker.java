// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopToolCall;
import com.cy.orrery.agent.runtime.loop.AgentStop;
import java.util.List;
import java.util.Map;

/**
 * LLM 调用 SPI。
 * <p>
 * 引擎与模型之间的归一化边界：实现负责把厂商差异（流式接收、截断续接、
 * 内容拒绝、token 统计口径）消化在内部，向引擎暴露统一的「正文 + 工具调用 + 用量」结果。
 * 流式正文应在接收期间经 {@code ctx.getBus()} 以 TEXT_DELTA 事件逐段下发，
 * 而非等整轮结束一次性返回。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface LlmInvoker {

    /**
     * 当前暴露给 LLM 的工具规格（OpenAI tools 格式）。
     *
     * @return 工具规格列表
     */
    List<Map<String, Object>> toolSpecs();

    /**
     * 执行一轮 LLM 调用。
     *
     * @param ctx        运行上下文（读取消息历史，发射流式事件）
     * @param roundIndex 轮次下标（从 0 起）
     * @param toolSpecs  工具规格
     * @return 归一化轮结果；失败时 {@link LlmRoundResult#failed()} 为 true
     */
    LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs);

    /**
     * 执行一轮带调用选项的 LLM 调用（结构化输出约束 / 工具选择策略）。
     * <p>
     * 默认实现忽略选项并回退到三参签名——未升级的适配器编译与行为均不受影响；
     * 支持选项的适配器应覆写本方法，把 {@link LlmCallOptions}
     * 翻译为厂商请求字段。
     * </p>
     *
     * @param ctx        运行上下文
     * @param roundIndex 轮次下标（从 0 起）
     * @param toolSpecs  工具规格
     * @param options    调用选项（{@code null} 视为 {@link LlmCallOptions#NONE}）
     * @return 归一化轮结果
     */
    default LlmRoundResult invoke(AgentRunContext ctx, int roundIndex,
                                  List<Map<String, Object>> toolSpecs,
                                  LlmCallOptions options) {
        return invoke(ctx, roundIndex, toolSpecs);
    }

    /**
     * 归一化 LLM 轮结果。
     *
     * @param content          正文（可空）
     * @param reasoning        深度思考内容（可空）
     * @param toolCalls        工具调用列表（可为空）
     * @param promptTokens     本轮 prompt token
     * @param completionTokens 本轮 completion token
     * @param errorCode        失败错误码（成功为 null）
     * @param errorMessage     失败消息（成功为 null）
     * @param stopHint         失败时建议的终止语义（如 OUTPUT_TRUNCATED；null 时引擎按 LLM_ERROR 处理）
     * @param failedRetryable  失败是否可重试（适配器表达；默认工厂按 true 供传输级瞬时失败）
     */
    record LlmRoundResult(
            String content,
            String reasoning,
            List<LoopToolCall> toolCalls,
            int promptTokens,
            int completionTokens,
            String errorCode,
            String errorMessage,
            AgentStop stopHint,
            boolean failedRetryable) {

        /**
         * 成功结果工厂。
         *
         * @param content          正文
         * @param reasoning        深度思考内容
         * @param toolCalls        工具调用
         * @param promptTokens     prompt token
         * @param completionTokens completion token
         * @return 成功结果
         */
        public static LlmRoundResult ok(String content, String reasoning,
                                        List<LoopToolCall> toolCalls, int promptTokens, int completionTokens) {
            return new LlmRoundResult(content, reasoning, toolCalls, promptTokens, completionTokens,
                    null, null, null, true);
        }

        /**
         * 失败结果工厂（传输级瞬时失败语义，可重试）。
         *
         * @param errorCode    错误码
         * @param errorMessage 失败消息
         * @param stopHint     终止语义建议
         * @return 失败结果
         */
        public static LlmRoundResult fail(String errorCode, String errorMessage, AgentStop stopHint) {
            return fail(errorCode, errorMessage, stopHint, true);
        }

        /**
         * 失败结果工厂。
         *
         * @param errorCode       错误码
         * @param errorMessage    失败消息
         * @param stopHint        终止语义建议
         * @param failedRetryable 失败是否可重试
         * @return 失败结果
         */
        public static LlmRoundResult fail(String errorCode, String errorMessage, AgentStop stopHint,
                                          boolean failedRetryable) {
            return new LlmRoundResult(null, null, List.of(), 0, 0,
                    errorCode, errorMessage, stopHint, failedRetryable);
        }

        /**
         * 是否失败。
         *
         * @return true 表示失败
         */
        public boolean failed() {
            return errorCode != null;
        }
    }
}
