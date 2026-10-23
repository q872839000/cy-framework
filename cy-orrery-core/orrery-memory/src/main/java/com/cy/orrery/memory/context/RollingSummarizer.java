// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.orrery.memory.api.ChatPort;
import com.cy.orrery.memory.prompt.PromptKey;
import com.cy.orrery.memory.prompt.PromptResolver;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 滚动摘要器（第二级抢救：被驱逐的最旧轮次与既有摘要 LLM 合并，杜绝静默丢弃）。
 * <p>
 * LLM 不可用时降级为"驱逐段原文截断拼接"（信息不丢，仅无压缩）。
 * </p>
 *
 * @author pcy
 */
public class RollingSummarizer {

    private static final Logger log = LoggerFactory.getLogger(RollingSummarizer.class);

    /** 降级路径下合并文本的最大字符数（超出截断保留尾部——最新信息优先）。 */
    private static final int FALLBACK_MAX_CHARS = 4000;

    /** LLM 对话端口。 */
    private final ChatPort chatPort;

    /** 提示词解析器。 */
    private final PromptResolver prompts;

    /**
     * 构造滚动摘要器。
     *
     * @param chatPort LLM 对话端口（null 或不可用时走降级拼接）
     * @param prompts  提示词解析器
     */
    public RollingSummarizer(ChatPort chatPort, PromptResolver prompts) {
        this.chatPort = chatPort;
        this.prompts = prompts;
    }

    /**
     * LLM 是否可用（不可用走降级拼接）。
     *
     * @return 可用性
     */
    public boolean llmAvailable() {
        return chatPort != null && chatPort.available();
    }

    /**
     * 滚动合并摘要。
     *
     * @param previousSummary 既有摘要（null 表示首次）
     * @param evictedLines    被驱逐的对话行（"角色：内容" 格式）
     * @return 新摘要
     */
    public String summarize(String previousSummary, List<String> evictedLines) {
        String transcript = evictedLines == null ? "" : String.join("\n", evictedLines).strip();
        if (transcript.isEmpty()) {
            return previousSummary;
        }
        if (!llmAvailable()) {
            return fallback(previousSummary, transcript);
        }
        try {
            String user = "EXISTING SUMMARY:\n" + (previousSummary == null || previousSummary.isBlank()
                    ? "(none — this is the first summary)" : previousSummary)
                    + "\nEVICTED SEGMENT:\n" + transcript;
            String summary = chatPort.chat(prompts.resolve(PromptKey.CONTEXT_SUMMARY), user);
            if (summary == null || summary.isBlank()) {
                return fallback(previousSummary, transcript);
            }
            return summary.strip();
        } catch (RuntimeException e) {
            log.warn("滚动摘要 LLM 调用失败（降级原文拼接）: {}", e.getMessage());
            return fallback(previousSummary, transcript);
        }
    }

    /** 降级：既有摘要 + 驱逐段原文（尾部截断，信息不丢但无压缩）。 */
    private String fallback(String previousSummary, String transcript) {
        String merged = (previousSummary == null || previousSummary.isBlank())
                ? transcript : previousSummary + "\n" + transcript;
        if (merged.length() <= FALLBACK_MAX_CHARS) {
            return merged;
        }
        return "…" + merged.substring(merged.length() - FALLBACK_MAX_CHARS);
    }
}
