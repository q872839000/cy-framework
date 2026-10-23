package com.cy.ai.model;

import java.util.Map;

/**
 * 响应格式（response_format）标准取值
 * <p>
 * 统一结构化输出请求的 response_format 构造，避免各调用方散落魔法 Map。
 * 约定：凡期望 JSON 正文的调用一律采用「API 级约束 + 提示词格式指令」双保险，
 * 不允许仅依赖提示词约束。
 * </p>
 *
 * @author pcy
 * @date 2026/10/15
 */
public final class ResponseFormats {

    private ResponseFormats() {
    }

    /**
     * 强制模型输出合法 JSON 对象（OpenAI json_object 模式）。
     * 提示词中须同时含 JSON 格式指令（该模式的前置条件）。
     */
    public static Map<String, Object> jsonObject() {
        return Map.of("type", "json_object");
    }
}
