package com.cy.conversation.session.service;

import com.cy.conversation.session.entity.ConversationSession;
import com.cy.conversation.session.vo.SessionQueryCondition;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 会话服务接口
 * <p>
 * 提供会话的增删改查功能
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
public interface SessionService extends IService<ConversationSession> {

    /**
     * 创建会话
     * <p>
     * 如果不传标题，会使用默认标题"新的对话"
     * </p>
     *
     * @param title 会话标题（可选）
     * @return 创建的会话实体
     */
    ConversationSession createSession(String title);

    /**
     * 更新会话标题
     *
     * @param id    会话ID
     * @param title 新的标题
     * @return 是否更新成功
     */
    Boolean updateTitle(Long id, String title);

    /**
     * 更新会话级模型偏好（全量提交语义：null = 清除覆盖、跟随平台默认）
     *
     * @param id           会话ID
     * @param llmProvider  LLM 提供商（cy.ai.providers 的 key）
     * @param llmModel     LLM 模型覆盖
     * @param thinkingPref 思考偏好（off / on / low / medium / high）
     * @return 是否更新成功
     */
    Boolean updateModelPreference(Long id, String llmProvider, String llmModel, String thinkingPref);

    /**
     * 更新会话命名状态
     *
     * @param id           会话ID
     * @param namingStatus 命名状态
     * @return 是否更新成功
     */
    Boolean updateNamingStatus(Long id, Integer namingStatus);

    /**
     * 更新会话标题和命名状态
     *
     * @param id           会话ID
     * @param title        新的标题
     * @param namingStatus 命名状态
     * @return 是否更新成功
     */
    Boolean updateTitleAndNamingStatus(Long id, String title, Integer namingStatus);

    /**
     * 删除会话（逻辑删除）
     *
     * @param id 会话ID
     * @return 是否删除成功
     */
    Boolean deleteSession(Long id);

    /**
     * 根据ID查询会话
     *
     * @param id 会话ID
     * @return 会话实体
     */
    ConversationSession getSessionById(Long id);

    /**
     * 分页查询当前用户的会话列表
     *
     * @param condition 查询条件
     * @return 分页结果
     */
    Page<ConversationSession> pageUserSessions(SessionQueryCondition condition);

    /**
     * 查询当前用户的所有会话
     *
     * @return 会话列表
     */
    List<ConversationSession> listUserSessions();

    /**
     * 增加会话消息数量
     *
     * @param sessionId 会话ID
     * @param count     增加的数量
     */
    void incrementMessageCount(Long sessionId, int count);

    /**
     * 检查会话是否属于当前用户
     *
     * @param sessionId 会话ID
     * @return 是否属于当前用户
     */
    boolean isSessionOwner(Long sessionId);

    /**
     * 验证会话访问权限
     * <p>
     * 检查会话是否存在以及当前用户是否有权访问。
     * 相比 isSessionOwner，此方法会抛出明确的异常区分"会话不存在"和"无权访问"。
     * </p>
     *
     * @param sessionId 会话ID
     * @throws com.cy.core.exception.CYException 30002-会话不存在，30003-无权访问
     */
    void validateSessionAccess(Long sessionId);

    /**
     * 异步生成会话标题
     * <p>
     * 在首条AI回复生成后异步调用大模型生成标题。
     * 成功后自动更新会话标题和命名状态。
     * </p>
     *
     * @param sessionId   会话ID
     * @param userMessage 用户首条消息
     * @param aiResponse  AI首条回复
     */
    void asyncGenerateTitle(Long sessionId, String userMessage, String aiResponse);

    /**
     * 同步生成会话标题
     *
     * @param userMessage 用户消息
     * @param aiResponse  AI回复
     * @return 生成的标题
     */
    String generateTitle(String userMessage, String aiResponse);
}
