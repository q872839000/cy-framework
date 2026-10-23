package com.cy.conversation.chat.service;

import com.cy.conversation.chat.vo.ChatRequest;
import com.cy.conversation.chat.vo.ChatStreamResponse;
import reactor.core.publisher.Flux;

/**
 * 聊天服务接口
 * <p>
 * 提供与大模型对话的核心功能
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
public interface ChatService {

    /**
     * 流式对话
     * <p>
     * 发送用户消息并实时返回AI响应片段。
     * 如果会话ID为空，会自动创建新会话。
     * 完成后会自动触发智能命名（如果是首次对话）。
     * </p>
     *
     * @param request 聊天请求
     * @return 流式响应的Flux
     */
    Flux<ChatStreamResponse> streamChat(ChatRequest request);

    /**
     * 检查指定会话是否有活跃的流式输出
     *
     * @param sessionId 会话ID
     * @return 是否活跃
     */
    boolean isStreamActive(Long sessionId);

    /**
     * 重连到活跃的流式会话
     * <p>
     * 返回该会话已缓存的所有响应块 + 后续实时块。
     * 如果不存在活跃流，返回空 Flux。
     * </p>
     *
     * @param sessionId 会话ID
     * @return 流式响应的Flux
     */
    Flux<ChatStreamResponse> resumeStream(Long sessionId);
}
