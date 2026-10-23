// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;


import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 结构化输出调用器：强制 JSON 输出 + 解析 + 带错误反馈的有界重试。
 * <p>
 * 对标 LangChain {@code with_structured_output} 的 ToolStrategy 语义：经
 * {@link LlmCallOptions} 向适配器透传 response_format（ProviderStrategy 通道由适配器
 * 决定是否启用厂商原生约束），拿到正文后在本类完成 JSON 解析；解析失败时把错误反馈
 * 追加为临时 user 消息再调一次（模型可自纠），达到重试上限返回失败。
 * 临时反馈消息在返回前撤除，不污染运行上下文消息历史。
 * </p>
 * <p>
 * 典型用途：意图分类、计划账本生成、参数抽取等「一次性结构化调用」（非 ReAct 轮次内）。
 * 本类不参与引擎主循环——引擎的 ReAct 轮仍走 {@link LlmInvoker#invoke} 三参签名。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class StructuredOutputInvoker implements LlmInvoker {

    private static final Logger log = LoggerFactory.getLogger(StructuredOutputInvoker.class);

    private final LlmInvoker delegate;
    private final ObjectMapper objectMapper;
    private final int maxParseRetries;

    /**
     * 构造结构化输出调用器（默认解析重试 2 次）。
     *
     * @param delegate     被包装的 LLM 调用 SPI
     * @param objectMapper JSON 解析器
     */
    public StructuredOutputInvoker(LlmInvoker delegate, ObjectMapper objectMapper) {
        this(delegate, objectMapper, 2);
    }

    /**
     * 构造结构化输出调用器。
     *
     * @param delegate        被包装的 LLM 调用 SPI
     * @param objectMapper    JSON 解析器
     * @param maxParseRetries 解析失败最大重试次数（≥0；0 表示不重试）
     */
    public StructuredOutputInvoker(LlmInvoker delegate, ObjectMapper objectMapper, int maxParseRetries) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.maxParseRetries = Math.max(0, maxParseRetries);
    }

    @Override
    public List<Map<String, Object>> toolSpecs() {
        return delegate.toolSpecs();
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs) {
        return delegate.invoke(ctx, roundIndex, toolSpecs);
    }

    @Override
    public LlmRoundResult invoke(AgentRunContext ctx, int roundIndex, List<Map<String, Object>> toolSpecs,
                                 LlmCallOptions options) {
        return delegate.invoke(ctx, roundIndex, toolSpecs, options);
    }

    /**
     * 结构化调用结果。
     *
     * @param json  解析出的 JSON（失败为 null）
     * @param raw   最后一次 LLM 原始轮结果
     * @param error 失败说明（成功为 null）
     */
    public record StructuredResult(JsonNode json, LlmRoundResult raw, String error) {

        /**
         * 是否成功。
         *
         * @return true 表示拿到合法 JSON
         */
        public boolean ok() {
            return json != null;
        }
    }

    /**
     * 执行一次结构化调用：按给定选项强制 JSON 输出，解析失败带反馈重试。
     *
     * @param ctx     运行上下文（读取消息历史；临时反馈消息返回前撤除）
     * @param options 调用选项（通常为 {@link LlmCallOptions#jsonObject()} 或
     *                {@link LlmCallOptions#jsonSchema}；{@code null} 按 jsonObject 处理）
     * @return 结构化结果
     */
    public StructuredResult invokeJson(AgentRunContext ctx, LlmCallOptions options) {
        LlmCallOptions effective = options == null || options.isEmpty()
                ? LlmCallOptions.jsonObject() : options;
        int tempMessages = 0;
        try {
            LlmRoundResult result = delegate.invoke(ctx, 0, List.of(), effective);
            for (int attempt = 0; ; attempt++) {
                if (result.failed()) {
                    return new StructuredResult(null, result,
                            result.errorMessage() == null ? result.errorCode() : result.errorMessage());
                }
                JsonNode json = tryParse(result.content());
                if (json != null) {
                    return new StructuredResult(json, result, null);
                }
                if (attempt >= maxParseRetries) {
                    log.warn("结构化输出解析失败，已达重试上限 runId={} content={}",
                            ctx.getRunId(), abbreviate(result.content()));
                    return new StructuredResult(null, result,
                            "输出不是合法 JSON，重试 " + attempt + " 次后仍失败");
                }
                // 解析失败：挂载错误反馈（含上轮正文，供模型对照自纠）后重试
                tempMessages += appendParseFeedback(ctx, result.content());
                result = delegate.invoke(ctx, 0, List.of(), effective);
            }
        } finally {
            LlmMessageOps.removeLastMessages(ctx, tempMessages);
        }
    }

    /**
     * 挂载解析失败的错误反馈消息（assistant 原文 + user 纠正指令）。
     *
     * @param ctx     运行上下文
     * @param content 上轮正文（非合法 JSON）
     * @return 挂载的消息条数（供 finally 撤除）
     */
    private int appendParseFeedback(AgentRunContext ctx, String content) {
        ctx.addMessage(LoopMessage.assistant(content, null, null));
        ctx.addMessage(LoopMessage.user(
                "上次输出不是合法 JSON（解析失败）。请仅输出一个合法 JSON 对象，不要输出任何解释或 Markdown 代码围栏。"));
        return 2;
    }

    /**
     * 尝试解析 JSON（容忍首尾空白与 Markdown 代码围栏）。
     *
     * @param content 正文
     * @return JSON 节点；解析失败返回 null
     */
    private JsonNode tryParse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (start > 0 && end > start) {
                text = text.substring(start + 1, end).strip();
            }
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            // 结构化输出契约是 JSON 对象："42" / "\"text\"" 等合法 JSON 标量
            // 不是对象，按解析失败处理（调用方按字段取值会 NPE / 错值）
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 日志用正文缩写。
     *
     * @param content 正文
     * @return 缩写（≤120 字符）
     */
    private String abbreviate(String content) {
        if (content == null) {
            return "null";
        }
        return content.length() <= 120 ? content : content.substring(0, 120) + "…";
    }
}
