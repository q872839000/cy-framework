// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;

import java.util.List;

/**
 * 运行时消息模型（领域无关的对话消息最小建模）。
 * <p>
 * 与 OpenAI 消息协议字段一一对应（role / content / reasoning / tool_calls / tool_call_id），
 * 宿主接入时在适配层与自有消息模型互转。工具回执消息通过 {@link #toolCallId} 与
 * assistant 消息的 {@link #toolCalls} 配对，维持 tool_calls 协议完整性。
 * </p>
 *
 * @param role             角色：system / user / assistant / tool
 * @param content          正文（assistant 纯 tool_calls 消息为空串，不为 null——qwen 等厂商要求字段存在）
 * @param reasoningContent 深度思考内容（可空）
 * @param toolCallId       tool 角色消息对应的调用 ID（其它角色为 null）
 * @param toolCalls        assistant 消息携带的工具调用列表（其它角色为 null）
 * @param imageUrls        user 消息携带的图片地址列表（data URI 或 http(s) URL，可空；
 *                         随消息进检查点，审批续跑 / 重放后仍在位）
 * @author pcy
 * @since 2026/08/19
 */
public record LoopMessage(
        String role,
        String content,
        String reasoningContent,
        String toolCallId,
        List<LoopToolCall> toolCalls,
        List<String> imageUrls) {

    /**
     * 兼容构造（无图片）：与既有五参调用点保持一致。
     */
    public LoopMessage(String role, String content, String reasoningContent,
                       String toolCallId, List<LoopToolCall> toolCalls) {
        this(role, content, reasoningContent, toolCallId, toolCalls, null);
    }

    /** system 角色。 */
    public static final String ROLE_SYSTEM = "system";

    /** user 角色。 */
    public static final String ROLE_USER = "user";

    /** assistant 角色。 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** tool 角色。 */
    public static final String ROLE_TOOL = "tool";

    /**
     * 构造 system 消息。
     *
     * @param content 正文
     * @return system 消息
     */
    public static LoopMessage system(String content) {
        return new LoopMessage(ROLE_SYSTEM, content, null, null, null);
    }

    /**
     * 构造 user 消息。
     *
     * @param content 正文
     * @return user 消息
     */
    public static LoopMessage user(String content) {
        return new LoopMessage(ROLE_USER, content, null, null, null);
    }

    /**
     * 构造携带图片的 user 消息（多模态）。
     * <p>
     * 图片地址随消息进入运行上下文与检查点，由 LLM 调用适配层翻译为
     * OpenAI content parts（image_url）；宿主应先在入口层完成视觉能力门控
     * （目标模型不支持图片时拒绝请求），本层不重复校验。
     * </p>
     *
     * @param content   正文（可为 null / 空白）
     * @param imageUrls 图片地址列表（data URI 或 http(s) URL）
     * @return user 消息
     */
    public static LoopMessage userWithImages(String content, List<String> imageUrls) {
        return new LoopMessage(ROLE_USER, content, null, null, null,
                imageUrls == null || imageUrls.isEmpty() ? null : List.copyOf(imageUrls));
    }

    /**
     * 构造 assistant 消息。
     * <p>
     * content 空白时置空串：部分厂商（qwen 等）要求 assistant 消息的 content 字段必须存在，
     * 即使只有 tool_calls 也不能为 null，统一用空串兜底。
     * </p>
     *
     * @param content   正文（可为 null / 空白）
     * @param reasoning 深度思考内容（可为 null）
     * @param toolCalls 工具调用列表（可为 null）
     * @return assistant 消息
     */
    public static LoopMessage assistant(String content, String reasoning, List<LoopToolCall> toolCalls) {
        return new LoopMessage(ROLE_ASSISTANT,
                content == null || content.isBlank() ? "" : content,
                reasoning == null || reasoning.isBlank() ? null : reasoning,
                null,
                toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls));
    }

    /**
     * 构造 tool 角色回执消息。
     *
     * @param toolCallId 对应的调用 ID
     * @param content    回执正文（通常为 JSON）
     * @return tool 消息
     */
    public static LoopMessage tool(String toolCallId, String content) {
        return new LoopMessage(ROLE_TOOL, content, null, toolCallId, null);
    }
}
