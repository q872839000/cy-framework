package com.cy.conversation.session.service.impl;

import com.cy.conversation.metrics.ConversationMetrics;
import com.cy.conversation.session.dao.ConversationSessionMapper;
import com.cy.conversation.session.entity.ConversationSession;
import com.cy.conversation.session.service.SessionService;
import com.cy.conversation.session.vo.SessionQueryCondition;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.exception.CYException;
import com.cy.ai.AiFacade;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * 会话服务实现类
 * <p>
 * 实现会话的增删改查功能，支持：
 * - 创建会话（带默认标题）
 * - 更新会话标题
 * - 逻辑删除会话
 * - 分页查询用户会话
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Service
public class SessionServiceImpl extends ServiceImpl<ConversationSessionMapper, ConversationSession>
        implements SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionServiceImpl.class);

    /**
     * 默认会话标题
     */
    private static final String DEFAULT_SESSION_TITLE = "新的对话";

    /**
     * 标题最小长度
     */
    private static final int TITLE_MIN_LENGTH = 2;

    /**
     * 标题最大长度
     */
    private static final int TITLE_MAX_LENGTH = 20;

    /**
     * 降级标题前缀最大长度
     */
    private static final int FALLBACK_PREFIX_LENGTH = 10;

    /**
     * 时间戳格式
     */
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("MMdd_HHmm");

    private final AiFacade aiFacade;
    private final ConversationMetrics metrics;

    /**
     * 命名提示词模板
     */
    private String promptTemplate;

    /**
     * 构造函数
     *
     * @param aiFacade AI 统一门面
     * @param metrics  指标采集器（可为null）
     */
    public SessionServiceImpl(AiFacade aiFacade,
                              ConversationMetrics metrics) {
        this.aiFacade = aiFacade;
        this.metrics = metrics;
        loadPromptTemplate();
    }

    /**
     * 从配置文件加载提示词模板
     */
    private void loadPromptTemplate() {
        try {
            ClassPathResource resource = new ClassPathResource("prompt.properties");
            if (resource.exists()) {
                Properties props = new Properties();
                try (InputStream is = resource.getInputStream();
                     InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
                this.promptTemplate = props.getProperty("naming.prompt", getDefaultPromptTemplate());
            } else {
                this.promptTemplate = getDefaultPromptTemplate();
            }
            log.info("加载命名提示词模板成功");
        } catch (IOException e) {
            log.warn("加载命名提示词模板失败，使用默认模板", e);
            this.promptTemplate = getDefaultPromptTemplate();
        }
    }

    /**
     * 获取默认提示词模板
     */
    private String getDefaultPromptTemplate() {
        return "根据以下对话生成一个简短标题（2-10字，无标点）：\n\n" +
               "【对话内容】\n问：{userMessage}\n答：{aiResponse}\n\n【标题】";
    }

    /**
     * {@inheritDoc}
     * <p>
     * 通过 {@link InvocationContextAccessor} 隐式获取当前用户上下文，
     * 用于日志记录和 ORM 自动填充。
     * </p>
     */
    @Override
    @Transactional
    public ConversationSession createSession(String title) {
        Long userId = InvocationContextAccessor.currentUserIdRequired();
        ConversationSession session = new ConversationSession();

        // 设置标题，如果为空则使用默认标题
        session.setTitle(StringUtils.isBlank(title) ? DEFAULT_SESSION_TITLE : title);
        // 设置默认状态
        session.setStatus(ConversationSession.STATUS_NORMAL);
        // 设置命名状态为待命名
        session.setNamingStatus(ConversationSession.NAMING_STATUS_PENDING);
        // 设置消息数量为0
        session.setMessageCount(0);

        // 保存会话
        this.save(session);

        log.info("创建会话成功，会话ID: {}, 标题: {}, 用户ID: {}",
                session.getId(), session.getTitle(), userId);

        return session;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Boolean updateTitle(Long id, String title) {
        // 检查会话归属权
        checkSessionOwnership(id);

        ConversationSession session = new ConversationSession();
        session.setId(id);
        session.setTitle(title);

        boolean result = this.updateById(session);
        if (result) {
            log.info("更新会话标题成功，会话ID: {}, 新标题: {}", id, title);
        }
        return result;
    }

    @Override
    public Boolean updateModelPreference(Long id, String llmProvider, String llmModel, String thinkingPref) {
        // 检查会话归属权
        checkSessionOwnership(id);

        // 全量提交语义（null = 清除会话级覆盖）：整行加载后回写，ignoreNulls=false 放行 null 清除
        ConversationSession session = getById(id);
        if (session == null) {
            return false;
        }
        session.setLlmProvider(llmProvider);
        session.setLlmModel(llmModel);
        session.setThinkingPref(thinkingPref);
        boolean result = this.updateById(session, false);
        if (result) {
            log.info("更新会话模型偏好成功，会话ID: {}, provider: {}, model: {}, thinking: {}",
                    id, llmProvider, llmModel, thinkingPref);
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Boolean updateNamingStatus(Long id, Integer namingStatus) {
        ConversationSession session = new ConversationSession();
        session.setId(id);
        session.setNamingStatus(namingStatus);
        return this.updateById(session);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional
    public Boolean updateTitleAndNamingStatus(Long id, String title, Integer namingStatus) {
        ConversationSession session = new ConversationSession();
        session.setId(id);
        session.setTitle(title);
        session.setNamingStatus(namingStatus);

        boolean result = this.updateById(session);
        if (result) {
            log.info("更新会话标题和命名状态成功，会话ID: {}, 新标题: {}, 命名状态: {}",
                    id, title, namingStatus);
        }
        return result;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 删除前检查会话归属权，确保只能删除自己的会话。
     * </p>
     */
    @Override
    @Transactional
    public Boolean deleteSession(Long id) {
        Long userId = InvocationContextAccessor.currentUserIdRequired();
        // 检查会话归属权
        checkSessionOwnership(id);

        boolean result = this.removeById(id);
        if (result) {
            log.info("删除会话成功，会话ID: {}, 用户ID: {}", id, userId);
        }
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public ConversationSession getSessionById(Long id) {
        ConversationSession session = this.getById(id);
        if (session != null) {
            // 检查会话归属权
            checkSessionOwnership(session);
        }
        return session;
    }

    /**
     * {@inheritDoc}
     * <p>
     * 通过 {@link InvocationContextAccessor} 获取当前用户 ID 进行数据隔离。
     * </p>
     */
    @Override
    public Page<ConversationSession> pageUserSessions(SessionQueryCondition condition) {
        Long userId = InvocationContextAccessor.currentUserId();
        if (userId == null) {
            throw new CYException(30001); // 用户未登录
        }

        QueryWrapper query = QueryWrapper.create()
                // 只查询当前用户的会话
                .where(ConversationSession::getCreateUser).eq(userId);

        // 标题模糊查询
        Optional.ofNullable(condition.getTitle())
                .filter(StringUtils::isNotBlank)
                .ifPresent(title -> query.and(ConversationSession::getTitle).like(title));

        // 状态筛选
        Optional.ofNullable(condition.getStatus())
                .ifPresent(status -> query.and(ConversationSession::getStatus).eq(status));

        // 按更新时间倒序
        query.orderBy(ConversationSession::getUpdateTime, false);

        return this.page(new Page<>(condition.getPage(), condition.getSize()), query);
    }

    /** {@inheritDoc} */
    @Override
    public List<ConversationSession> listUserSessions() {
        Long userId = InvocationContextAccessor.currentUserId();
        if (userId == null) {
            throw new CYException(30001); // 用户未登录
        }

        QueryWrapper query = QueryWrapper.create()
                .where(ConversationSession::getCreateUser).eq(userId)
                .and(ConversationSession::getStatus).eq(ConversationSession.STATUS_NORMAL)
                .orderBy(ConversationSession::getUpdateTime, false);

        return this.list(query);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 使用 {@code SET message_count = message_count + count} 原子自增，避免并发读写丢失。
     * </p>
     */
    @Override
    @Transactional
    public void incrementMessageCount(Long sessionId, int count) {
        // 使用 UpdateWrapper 直接执行 SQL 原子自增，避免先读后写的并发问题；
        // 同时刷新 update_time，让会话按最新消息时间上浮到列表顶部
        ((ConversationSessionMapper) this.getMapper()).incrementMessageCount(sessionId, count);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isSessionOwner(Long sessionId) {
        Long userId = InvocationContextAccessor.currentUserId();
        if (userId == null) {
            return false;
        }

        ConversationSession session = this.getById(sessionId);
        return session != null && userId.equals(session.getCreateUser());
    }

    /** {@inheritDoc} */
    @Override
    public void validateSessionAccess(Long sessionId) {
        ConversationSession session = this.getById(sessionId);
        if (session == null) {
            throw new CYException(30002); // 会话不存在或已被删除
        }
        Long userId = InvocationContextAccessor.currentUserId();
        if (userId == null || !userId.equals(session.getCreateUser())) {
            throw new CYException(30003); // 无权访问该会话
        }
    }

    /**
     * 检查会话归属权
     * <p>
     * 验证当前用户是否有权限访问该会话
     * </p>
     *
     * @param sessionId 会话ID
     * @throws CYException 如果会话不存在或不属于当前用户
     */
    private void checkSessionOwnership(Long sessionId) {
        ConversationSession session = this.getById(sessionId);
        checkSessionOwnership(session);
    }

    /**
     * 检查会话归属权
     *
     * @param session 会话实体
     * @throws CYException 如果会话不存在或不属于当前用户
     */
    private void checkSessionOwnership(ConversationSession session) {
        if (session == null) {
            throw new CYException(30002); // 会话不存在
        }

        Long userId = InvocationContextAccessor.currentUserId();
        if (userId == null || !userId.equals(session.getCreateUser())) {
            throw new CYException(30003); // 无权访问该会话
        }
    }

    // ==================== 智能命名功能 ====================

    /**
     * {@inheritDoc}
     * <p>
     * 在 {@code conversationAsyncExecutor} 线程池中执行，
     * 通过 {@link com.cy.core.context.InvocationContextAccessor#wrap TaskDecorator} 自动传播上下文。
     * 失败时使用降级标题（用户消息前缀 + 时间戳）。
     * </p>
     */
    @Override
    @Async("conversationAsyncExecutor")
    public void asyncGenerateTitle(Long sessionId, String userMessage, String aiResponse) {
        log.info("开始异步生成会话标题，会话ID: {}", sessionId);

        try {
            // 更新命名状态为"命名中"
            updateNamingStatus(sessionId, ConversationSession.NAMING_STATUS_PROCESSING);

            // 生成标题
            String title = generateTitle(userMessage, aiResponse);

            if (StringUtils.isNotBlank(title)) {
                // 命名成功
                updateTitleAndNamingStatus(sessionId, title, ConversationSession.NAMING_STATUS_COMPLETED);
                log.info("会话命名成功，会话ID: {}, 标题: {}", sessionId, title);

                // 记录成功指标
                if (metrics != null) {
                    metrics.recordNamingResult(true);
                }
            } else {
                // 命名失败，使用降级策略
                String fallbackTitle = generateFallbackTitle(userMessage);
                updateTitleAndNamingStatus(sessionId, fallbackTitle, ConversationSession.NAMING_STATUS_FAILED);
                log.warn("会话命名失败（返回空），使用降级标题，会话ID: {}, 标题: {}", sessionId, fallbackTitle);

                // 记录失败指标
                if (metrics != null) {
                    metrics.recordNamingResult(false);
                }
            }

        } catch (Exception e) {
            log.error("会话命名异常，会话ID: {}", sessionId, e);

            // 使用降级策略
            try {
                String fallbackTitle = generateFallbackTitle(userMessage);
                updateTitleAndNamingStatus(sessionId, fallbackTitle, ConversationSession.NAMING_STATUS_FAILED);
                log.info("使用降级标题，会话ID: {}, 标题: {}", sessionId, fallbackTitle);
            } catch (Exception ex) {
                log.error("更新降级标题失败，会话ID: {}", sessionId, ex);
            }

            // 记录失败指标
            if (metrics != null) {
                metrics.recordNamingResult(false);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public String generateTitle(String userMessage, String aiResponse) {
        // 构建提示词
        String prompt = buildPrompt(userMessage, aiResponse);

        // 调用大模型生成标题（非流式，使用默认 Provider 和默认模型）
        String title = aiFacade.simpleChat(null, prompt);

        // 处理生成的标题
        return processTitle(title);
    }

    /**
     * 构建命名提示词
     */
    private String buildPrompt(String userMessage, String aiResponse) {
        String truncatedUserMsg = truncateContent(userMessage, 200);
        String truncatedAiResp = truncateContent(aiResponse, 500);

        return promptTemplate
                .replace("{userMessage}", truncatedUserMsg)
                .replace("{aiResponse}", truncatedAiResp);
    }

    /**
     * 截断内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    /**
     * 处理生成的标题
     */
    private String processTitle(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }

        // 去除首尾空白和引号
        title = title.trim()
                .replaceAll("^[\"'\u201c\u201d\u2018\u2019]+", "")
                .replaceAll("[\"'\u201c\u201d\u2018\u2019]+$", "")
                .trim();

        // 去除可能的标点符号
        title = title.replaceAll("[。！？,.!?]$", "");

        // 检查长度
        if (title.length() < TITLE_MIN_LENGTH) {
            return null;
        }

        // 截断过长的标题
        if (title.length() > TITLE_MAX_LENGTH) {
            title = title.substring(0, TITLE_MAX_LENGTH);
        }

        return title;
    }

    /**
     * 生成降级标题
     */
    private String generateFallbackTitle(String userMessage) {
        String prefix;
        if (StringUtils.isBlank(userMessage)) {
            prefix = "对话";
        } else if (userMessage.length() <= FALLBACK_PREFIX_LENGTH) {
            prefix = userMessage;
        } else {
            prefix = userMessage.substring(0, FALLBACK_PREFIX_LENGTH);
        }

        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        return prefix + "_" + timestamp;
    }
}
