package com.cy.conversation.message.dao;

import com.cy.conversation.message.entity.ChatMessage;
import com.mybatisflex.core.BaseMapper;

/**
 * 聊天消息数据访问接口
 * <p>
 * 基于MyBatis-Flex实现的消息数据访问层。
 * 提供消息的CRUD操作。
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
