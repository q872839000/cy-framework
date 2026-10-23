package com.cy.conversation.session.dao;

import com.cy.conversation.session.entity.ConversationSession;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 会话数据访问接口
 * <p>
 * 基于MyBatis-Flex实现的会话数据访问层。
 * 提供会话的CRUD操作。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
public interface ConversationSessionMapper extends BaseMapper<ConversationSession> {

    /**
     * 原子递增会话消息数量。
     * <p>
     * 使用 SQL {@code SET message_count = message_count + #{count}} 避免并发读写丢失；
     * 同步刷新 update_time——会话列表按 update_time 倒序，新消息必须让会话上浮。
     * update_time 是 TIMESTAMP 列，用 NOW() 取数据库当前时刻（与列类型/时区语义一致，
     * 不能传应用侧 epoch 毫秒）。
     * </p>
     *
     * @param sessionId 会话 ID
     * @param count     递增数量
     * @return 受影响行数
     */
    @Update("UPDATE conversation_session SET message_count = message_count + #{count}, "
            + "update_time = NOW() WHERE id = #{sessionId}")
    int incrementMessageCount(@Param("sessionId") Long sessionId, @Param("count") int count);
}
