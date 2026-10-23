// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;

import java.util.Map;

/**
 * LLM 单次调用选项：随调用变化的请求级参数（与工具规格解耦）。
 * <p>
 * 对标 OpenAI Chat Completions 的请求级开关：{@code response_format}（结构化输出约束）
 * 与 {@code tool_choice}（工具选择策略）。领域无关，适配器负责把选项翻译成厂商请求字段；
 * 不识别选项的适配器可安全忽略（见 {@link LlmInvoker} 的
 * options 默认方法契约）。
 * </p>
 *
 * @param responseFormat         响应格式约束（OpenAI response_format 结构；null 表示普通文本）
 * @param toolChoice             工具选择策略（"auto" / "none" / "required" / 指定函数对象；null 表示默认）
 * @param callId                 调用 ID（LLM 遥测事件关联用；null 表示不发射遥测）
 * @param suppressContentEmission 静默正文：true 时流式 content 增量不作为用户可见块下发
 *                                （结构化 JSON 路径——content 是待解析的 JSON 不是给用户的正文，
 *                                下发会刷屏）；reasoning 增量不受影响。被静默的增量不置
 *                                「已发射用户可见块」标记，不影响流式失败的重试判定。
 * @param label                  正文块标签（同一轮内多段思考 / 正文按业务阶段标注，如"思考·战术意图"；null 无标签）
 * @author pcy
 * @since 2026/08/27
 */
public record LlmCallOptions(Map<String, Object> responseFormat, Object toolChoice,
                             String callId, boolean suppressContentEmission, String label) {

    /** 空选项（普通文本输出 + 默认工具策略）。 */
    public static final LlmCallOptions NONE = new LlmCallOptions(null, null, null, false, null);

    /**
     * 构造选项。
     *
     * @param responseFormat 响应格式约束（可空）
     * @param toolChoice     工具选择策略（可空）
     * @return 选项
     */
    public static LlmCallOptions of(Map<String, Object> responseFormat, Object toolChoice) {
        return new LlmCallOptions(responseFormat, toolChoice, null, false, null);
    }

    /**
     * 强制 JSON 对象输出（{@code {"type":"json_object"}}）。
     *
     * @return JSON 对象输出选项
     */
    public static LlmCallOptions jsonObject() {
        return new LlmCallOptions(Map.of("type", "json_object"), null, null, true, null);
    }

    /**
     * 强制按 JSON Schema 输出（{@code {"type":"json_schema", ...}}，structured outputs）。
     *
     * @param name   schema 名称（厂商要求的小写标识）
     * @param schema JSON Schema 结构
     * @param strict 是否严格模式（厂商支持时）
     * @return JSON Schema 输出选项
     */
    public static LlmCallOptions jsonSchema(String name, Map<String, Object> schema, boolean strict) {
        return new LlmCallOptions(Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", name,
                        "schema", schema,
                        "strict", strict)), null, null, true, null);
    }

    /**
     * 派生选项：设置调用 ID（遥测关联）。
     *
     * @param callId 调用 ID
     * @return 新选项
     */
    public LlmCallOptions withCallId(String callId) {
        return new LlmCallOptions(responseFormat, toolChoice, callId, suppressContentEmission, label);
    }

    /**
     * 派生选项：设置正文块标签。
     *
     * @param label 块标签
     * @return 新选项
     */
    public LlmCallOptions withLabel(String label) {
        return new LlmCallOptions(responseFormat, toolChoice, callId, suppressContentEmission, label);
    }

    /**
     * 派生选项：是否静默正文增量。
     *
     * @param suppress 是否静默
     * @return 新选项
     */
    public LlmCallOptions withSuppressContent(boolean suppress) {
        return new LlmCallOptions(responseFormat, toolChoice, callId, suppress, label);
    }

    /**
     * 是否为空选项。
     *
     * @return true 表示无任何约束
     */
    public boolean isEmpty() {
        return responseFormat == null && toolChoice == null;
    }
}
