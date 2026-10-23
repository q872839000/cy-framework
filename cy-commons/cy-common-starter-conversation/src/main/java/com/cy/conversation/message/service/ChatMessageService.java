package com.cy.conversation.message.service;

import com.cy.conversation.message.entity.ChatMessage;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 聊天消息服务接口
 * <p>
 * 提供聊天消息的CRUD操作
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
public interface ChatMessageService extends IService<ChatMessage> {

    /**
     * 保存用户消息
     *
     * @param sessionId 会话ID
     * @param content   消息内容
     * @return 保存的消息实体
     */
    ChatMessage saveUserMessage(Long sessionId, String content);

    /**
     * 保存用户消息（可携带图片附件）
     *
     * @param sessionId 会话ID
     * @param content   消息内容（纯图片输入时可为空）
     * @param imageUrls 图片地址列表（data URI 或 http(s) URL，以 JSON 数组落附件列，可空）
     * @return 保存的消息实体
     */
    ChatMessage saveUserMessage(Long sessionId, String content, List<String> imageUrls);

    /**
     * 保存AI助手消息
     *
     * @param sessionId        会话ID
     * @param content          消息内容
     * @param reasoningContent 思考内容（可为 null）
     * @param model            使用的模型
     * @param tokens           Token消耗数
     * @param durationMs       响应耗时（毫秒）
     * @param firstTokenMs     首帧响应耗时（毫秒）
     * @return 保存的消息实体
     */
    ChatMessage saveAssistantMessage(Long sessionId, String content, String reasoningContent,
                                    String model, Integer tokens, Long durationMs,
                                    Long firstTokenMs);

    /**
     * 保存AI助手消息（携带运行过程快照）
     *
     * @param sessionId        会话ID
     * @param content          消息内容
     * @param reasoningContent 思考内容（可为 null）
     * @param model            使用的模型
     * @param tokens           Token消耗数
     * @param durationMs       响应耗时（毫秒）
     * @param firstTokenMs     首帧响应耗时（毫秒）
     * @param blocksJson       运行过程快照 JSON（events/reasonings/textBlocks/artifacts/meta；可为 null）
     * @return 保存的消息实体
     */
    ChatMessage saveAssistantMessage(Long sessionId, String content, String reasoningContent,
                                    String model, Integer tokens, Long durationMs,
                                    Long firstTokenMs, String blocksJson);

    /**
     * 保存系统消息
     *
     * @param sessionId 会话ID
     * @param content   消息内容
     * @return 保存的消息实体
     */
    ChatMessage saveSystemMessage(Long sessionId, String content);

    /**
     * 根据会话ID查询消息列表
     * <p>
     * 按创建时间正序排列
     * </p>
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    List<ChatMessage> listBySessionId(Long sessionId);

    /**
     * 根据会话ID分页查询消息
     *
     * @param sessionId 会话ID
     * @param page      页码
     * @param size      每页大小
     * @return 分页结果
     */
    Page<ChatMessage> pageBySessionId(Long sessionId, int page, int size);

    /**
     * 获取会话的最近N条消息
     * <p>
     * 用于构建对话历史上下文
     * </p>
     *
     * @param sessionId 会话ID
     * @param limit     最大消息数
     * @return 消息列表
     */
    List<ChatMessage> getRecentMessages(Long sessionId, int limit);

    /**
     * 获取会话的消息数量
     *
     * @param sessionId 会话ID
     * @return 消息数量
     */
    long countBySessionId(Long sessionId);

    /**
     * 删除会话的所有消息
     *
     * @param sessionId 会话ID
     * @return 删除的消息数量
     */
    int deleteBySessionId(Long sessionId);
}
