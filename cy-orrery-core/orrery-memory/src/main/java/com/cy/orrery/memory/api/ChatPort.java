// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * LLM 对话端口（摘要/提取/决策用；由 cy-common-ai 桥接实现）。
 *
 * @author pcy
 */
public interface ChatPort {

    /**
     * 单轮对话（system + user → text）。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 模型输出文本
     */
    String chat(String systemPrompt, String userMessage);

    /**
     * 期望 JSON 对象正文的单轮对话（API 级 response_format + 提示词双保险）。
     * <p>
     * 默认实现委托 {@link #chat} 兜底：不具备 API 级约束能力的端口
     * （如测试桩）退化为纯提示词约束，解析侧原有容错语义不受影响。
     * </p>
     *
     * @param systemPrompt 系统提示词（须含 JSON 格式指令）
     * @param userMessage  用户消息
     * @return 模型输出文本
     */
    default String chatJson(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage);
    }

    /**
     * 是否可用（无 LLM 时摘要降级原文拼接、巩固管线停用）。
     *
     * @return 可用性
     */
    boolean available();
}
