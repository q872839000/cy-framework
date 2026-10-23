// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;

/**
 * LLM 发起的工具调用（对 OpenAI tool_calls 协议的最小建模）。
 *
 * @param id        调用 ID（LLM 分配，tool 消息回执按此配对）
 * @param name      工具名
 * @param arguments 参数 JSON 字符串（未解析原文）
 * @author pcy
 * @since 2026/08/19
 */
public record LoopToolCall(String id, String name, String arguments) {
}
