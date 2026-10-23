// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文会话默认实现（决策流：估算 → 折叠 → 驱逐+摘要）。
 * <p>
 * 两级抢救：折叠（tool 大消息换占位符，不调 LLM）压不住才驱逐最旧
 * user/assistant 轮次并入滚动摘要（LLM，失败降级原文拼接）。
 * 保留区（最近 N 轮）任何水位下永不折叠、永不驱逐。
 * </p>
 *
 * @author pcy
 */
public class DefaultContextSession implements ContextSession {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextSession.class);

    /** 驱逐时摘要合并的批次轮次（每轮 = 一个 user 消息起的连续段）。 */
    private static final int EVICT_BATCH_ROUNDS = 2;

    /** 摘要消息角色（system 段：宿主拼装在自身 system 提示之后）。 */
    private static final String ROLE_SUMMARY = ChatMessage.ROLE_SYSTEM;

    /** 会话 ID。 */
    private final String sessionId;

    /** 窗口配置。 */
    private final ContextWindowConfig config;

    /** 窗口快照（内存工作副本，close 时写回）。 */
    private final ContextWindow window;

    /** 窗口存储（null = 仅 run 内存活）。 */
    private final ContextWindowStore store;

    /** 滚动摘要器。 */
    private final RollingSummarizer summarizer;

    /** 宿主侧消息适配器（组件内部消息的固定实现）。 */
    private final ChatMessageAdapter adapter = new ChatMessageAdapter();

    /** 是否有未落库变更。 */
    private boolean dirty;

    /** 是否已关闭。 */
    private boolean closed;

    /**
     * 构造上下文会话（通常由 {@link ContextManager#open} 创建，不直接实例化）。
     *
     * @param sessionId  会话 ID
     * @param config     窗口配置
     * @param window     窗口快照（内存工作副本，close 时写回）
     * @param store      窗口存储（null = 仅 run 内存活）
     * @param summarizer 滚动摘要器
     */
    public DefaultContextSession(String sessionId, ContextWindowConfig config,
                                 ContextWindow window, ContextWindowStore store,
                                 RollingSummarizer summarizer) {
        this.sessionId = sessionId;
        this.config = config;
        this.window = window;
        this.store = store;
        this.summarizer = summarizer;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public void append(List<ChatMessage> messages) {
        assertOpen();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        window.getMessages().addAll(messages);
        dirty = true;
    }

    @Override
    public List<ChatMessage> prepareForLlm() {
        assertOpen();
        if (config.enabled()) {
            watchWindow();
        }
        List<ChatMessage> out = new ArrayList<>();
        if (window.getSummary() != null && !window.getSummary().isBlank()) {
            out.add(ChatMessage.of(ROLE_SUMMARY, window.getSummary()));
        }
        out.addAll(window.getMessages());
        return out;
    }

    @Override
    public String currentSummary() {
        return window.getSummary();
    }

    @Override
    public List<ChatMessage> messages() {
        return List.copyOf(window.getMessages());
    }

    /**
     * 窗口守望：超阈值先折叠，压不住再驱逐+摘要（循环直到回到水位内）。
     */
    private void watchWindow() {
        int estimated = estimateWindow();
        if (estimated < config.normalThresholdTokens()) {
            return;
        }
        boolean hardTier = config.hardThresholdTokens() > 0
                && estimated >= config.hardThresholdTokens();
        int keepRounds = hardTier ? 1 : config.recentKeepRounds();

        int keepFrom = ContextCompactor.resolveKeepFromIndex(
                window.getMessages(), adapter, keepRounds, 0);
        int elided = ContextCompactor.compact(window.getMessages(), adapter, 0, null, keepFrom);
        if (elided > 0) {
            dirty = true;
            log.info("上下文折叠: sessionId={}, tier={}, elided={}",
                    sessionId, hardTier ? "hard" : "normal", elided);
        }

        while (estimateWindow() >= config.normalThresholdTokens() && evictOldestIntoSummary()) {
            // 驱逐+摘要循环：每轮把最旧批次并入摘要，直到回到水位内或无旧轮次可驱逐
        }
    }

    /**
     * 驱逐最旧一批轮次并入滚动摘要。
     *
     * @return 是否发生了驱逐（false = 无可驱逐内容，循环终止）
     */
    private boolean evictOldestIntoSummary() {
        List<ChatMessage> messages = window.getMessages();
        int keepFrom = ContextCompactor.resolveKeepFromIndex(messages, adapter,
                config.recentKeepRounds(), 0);
        if (keepFrom <= 0) {
            return false;
        }
        // 找第 EVICT_BATCH_ROUNDS 个轮次起点作为驱逐终点（保留区外按批驱逐）
        int evictTo = 0;
        int rounds = 0;
        for (int i = 0; i < keepFrom; i++) {
            if (ChatMessage.ROLE_USER.equals(messages.get(i).getRole())) {
                rounds++;
                if (rounds >= EVICT_BATCH_ROUNDS) {
                    evictTo = i;
                    break;
                }
            }
            evictTo = i + 1;
        }
        if (evictTo <= 0) {
            return false;
        }
        List<ChatMessage> evicted = new ArrayList<>(messages.subList(0, evictTo));
        List<String> lines = new ArrayList<>();
        for (ChatMessage m : evicted) {
            if (m.isTurnMessage() && m.getContent() != null && !m.getContent().isBlank()) {
                lines.add(turnSpeaker(m.getRole()) + "：" + m.getContent().strip());
            }
        }
        if (!lines.isEmpty()) {
            window.setSummary(summarizer.summarize(window.getSummary(), lines));
        }
        messages.subList(0, evictTo).clear();
        dirty = true;
        log.info("上下文驱逐并入摘要: sessionId={}, evictedMessages={}", sessionId, evictTo);
        return true;
    }

    /**
     * 估算窗口总 token（摘要 + 消息）。
     *
     * @return token 估算
     */
    private int estimateWindow() {
        int total = TokenEstimator.estimate(window.getSummary());
        for (ChatMessage m : window.getMessages()) {
            total += TokenEstimator.estimate(m.getContent());
        }
        return total;
    }

    /**
     * 轮次角色的摘要行前缀。
     *
     * @param role 角色
     * @return 前缀
     */
    private String turnSpeaker(String role) {
        return ChatMessage.ROLE_USER.equals(role) ? "用户" : "助手";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (store == null) {
            return;
        }
        try {
            if (dirty) {
                window.setVersion(window.getVersion() + 1);
                boolean saved = store.save(sessionId, window, window.getVersion() - 1);
                if (!saved) {
                    // CAS 冲突 = 会话被另一活跃窗口写过，保留日志线索
                    log.warn("上下文窗口写回版本冲突（本次变更丢弃）: sessionId={}", sessionId);
                }
            }
        } finally {
            store.release(sessionId);
        }
    }

    /** 打开状态断言。 */
    private void assertOpen() {
        if (closed) {
            throw new IllegalStateException("上下文会话已关闭: " + sessionId);
        }
    }

    /**
     * 组件内部消息的适配器实现（ChatMessage 可变，折叠原地生效）。
     */
    private static final class ChatMessageAdapter implements MessageAdapter<ChatMessage> {

        @Override
        public String role(ChatMessage message) {
            return message.getRole();
        }

        @Override
        public String content(ChatMessage message) {
            return message.getContent();
        }

        @Override
        public ChatMessage withContent(ChatMessage message, String newContent) {
            message.setContent(newContent);
            return message;
        }

        @Override
        public boolean isFolded(ChatMessage message) {
            return message.isFolded();
        }

        @Override
        public ChatMessage fold(ChatMessage message, int originalChars) {
            message.setOriginalTokens(TokenEstimator.estimate(message.getContent()));
            message.setContent(FOLD_PLACEHOLDER_PREFIX + originalChars + " chars elided]");
            message.setFolded(true);
            return message;
        }
    }
}
