package com.cy.conversation.message.service.impl;

import com.cy.conversation.message.dao.ChatMessageMapper;
import com.cy.conversation.message.entity.ChatMessage;
import com.cy.conversation.message.service.ChatMessageService;
import com.cy.conversation.session.service.SessionService;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.exception.CYException;
import com.alibaba.fastjson2.JSON;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;

/**
 * 聊天消息服务实现类
 * <p>
 * 实现聊天消息的CRUD操作，包括：
 * - 保存用户/助手/系统消息
 * - 查询会话消息历史
 * - 获取最近消息（用于上下文）
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Service
@RequiredArgsConstructor
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage>
        implements ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    /** 会话服务，用于验证会话归属权和增加消息计数 */
    private final SessionService sessionService;

    /**
     * {@inheritDoc}
     * <p>
     * 保存前验证会话归属权，保存后自动增加会话消息计数。
     * 通过 {@link InvocationContextAccessor} 隐式获取当前用户上下文。
     * </p>
     */
    @Override
    @Transactional
    public ChatMessage saveUserMessage(Long sessionId, String content) {
        return saveUserMessage(sessionId, content, null);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 保存前验证会话归属权，保存后自动增加会话消息计数。
     * 通过 {@link InvocationContextAccessor} 隐式获取当前用户上下文。
     * </p>
     */
    @Override
    @Transactional
    public ChatMessage saveUserMessage(Long sessionId, String content, List<String> imageUrls) {
        Long userId = InvocationContextAccessor.currentUserIdRequired();

        // 验证会话归属权
        validateSessionOwnership(sessionId);

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(ChatMessage.ROLE_USER);
        message.setContent(content);
        if (imageUrls != null && !imageUrls.isEmpty()) {
            message.setAttachments(JSON.toJSONString(imageUrls));
        }
        message.setCreateTime(new Timestamp(System.currentTimeMillis()));
        message.setCreateUser(userId);

        this.save(message);

        // 增加会话消息数量
        sessionService.incrementMessageCount(sessionId, 1);

        log.debug("保存用户消息成功，会话ID: {}, 消息ID: {}, 附件数: {}",
                sessionId, message.getId(), imageUrls != null ? imageUrls.size() : 0);

        return message;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 助手消息不需要严格的用户归属校验，上下文为可选（系统调用场景可能无用户上下文）。
     * </p>
     */
    @Override
    @Transactional
    public ChatMessage saveAssistantMessage(Long sessionId, String content, String reasoningContent,
                                            String model, Integer tokens, Long durationMs,
                                            Long firstTokenMs) {
        return saveAssistantMessage(sessionId, content, reasoningContent, model, tokens,
                durationMs, firstTokenMs, null);
    }

    /**
     * {@inheritDoc}
     * <p>过程快照随消息落库（可空）：历史回看据此重建执行过程视图。</p>
     */
    @Override
    @Transactional
    public ChatMessage saveAssistantMessage(Long sessionId, String content, String reasoningContent,
                                            String model, Integer tokens, Long durationMs,
                                            Long firstTokenMs, String blocksJson) {
        Long userId = InvocationContextAccessor.currentUserId();

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(ChatMessage.ROLE_ASSISTANT);
        message.setContent(content);
        message.setReasoningContent(reasoningContent);
        message.setBlocksJson(blocksJson);
        message.setModel(model);
        message.setTokens(tokens);
        message.setDurationMs(durationMs);
        message.setFirstTokenMs(firstTokenMs);
        message.setCreateTime(new Timestamp(System.currentTimeMillis()));
        message.setCreateUser(userId);

        this.save(message);

        // 增加会话消息数量
        sessionService.incrementMessageCount(sessionId, 1);

        log.debug("保存助手消息成功，会话ID: {}, 消息ID: {}, Token数: {}, 耗时: {}ms, 过程快照: {}",
                sessionId, message.getId(), tokens, durationMs, blocksJson != null);

        return message;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 系统消息不需要会话归属校验，上下文为可选。
     * </p>
     */
    @Override
    @Transactional
    public ChatMessage saveSystemMessage(Long sessionId, String content) {
        Long userId = InvocationContextAccessor.currentUserId();
        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(ChatMessage.ROLE_SYSTEM);
        message.setContent(content);
        message.setCreateTime(new Timestamp(System.currentTimeMillis()));
        message.setCreateUser(userId);

        this.save(message);

        return message;
    }

    /** {@inheritDoc} */
    @Override
    public List<ChatMessage> listBySessionId(Long sessionId) {
        // 验证会话归属权
        validateSessionOwnership(sessionId);

        QueryWrapper query = QueryWrapper.create()
                .where(ChatMessage::getSessionId).eq(sessionId)
                .orderBy(ChatMessage::getCreateTime, true);

        return this.list(query);
    }

    /** {@inheritDoc} */
    @Override
    public Page<ChatMessage> pageBySessionId(Long sessionId, int page, int size) {
        // 验证会话归属权
        validateSessionOwnership(sessionId);

        QueryWrapper query = QueryWrapper.create()
                .where(ChatMessage::getSessionId).eq(sessionId)
                .orderBy(ChatMessage::getCreateTime, true);

        return this.page(new Page<>(page, size), query);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 实现策略：先按时间倒序取最近 N 条，再反转为正序，用于构建对话上下文。
     * </p>
     */
    @Override
    public List<ChatMessage> getRecentMessages(Long sessionId, int limit) {
        // 验证会话归属权
        validateSessionOwnership(sessionId);

        // 先按时间倒序查询最近的N条，然后再正序排列
        QueryWrapper query = QueryWrapper.create()
                .where(ChatMessage::getSessionId).eq(sessionId)
                .orderBy(ChatMessage::getCreateTime, false)
                .limit(limit);

        List<ChatMessage> messages = this.list(query);

        // 反转列表使其按时间正序排列
        java.util.Collections.reverse(messages);

        return messages;
    }

    /** {@inheritDoc} */
    @Override
    public long countBySessionId(Long sessionId) {
        QueryWrapper query = QueryWrapper.create()
                .where(ChatMessage::getSessionId).eq(sessionId);
        return this.count(query);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public int deleteBySessionId(Long sessionId) {
        // 验证会话归属权
        validateSessionOwnership(sessionId);

        QueryWrapper query = QueryWrapper.create()
                .where(ChatMessage::getSessionId).eq(sessionId);

        long count = this.count(query);
        this.remove(query);

        log.info("删除会话消息成功，会话ID: {}, 删除数量: {}", sessionId, count);

        return (int) count;
    }

    /**
     * 验证会话归属权
     * <p>
     * 使用 SessionService 的 validateSessionAccess 方法，
     * 可以区分"会话不存在"和"无权访问"两种错误情况
     * </p>
     *
     * @param sessionId 会话ID
     * @throws CYException 30002-会话不存在，30003-无权访问
     */
    private void validateSessionOwnership(Long sessionId) {
        sessionService.validateSessionAccess(sessionId);
    }
}
