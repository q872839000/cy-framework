// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.orrery.memory.api.ChatPort;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.prompt.LlmOutput;
import com.cy.orrery.memory.prompt.PromptKey;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 事实提取器（巩固管线第一步：对话段 → 候选记忆，LLM 单跳）。
 *
 * @author pcy
 */
public class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);

    /** 默认重要度（LLM 未给出时的中位值；与 {@link SemanticMemoryService} 写入路径同源）。 */
    private static final int DEFAULT_IMPORTANCE = SemanticMemoryService.DEFAULT_IMPORTANCE;

    /** 重要度下限。 */
    private static final int MIN_IMPORTANCE = 1;

    /** 重要度上限。 */
    private static final int MAX_IMPORTANCE = 10;

    /** JSON 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** LLM 对话端口。 */
    private final ChatPort chatPort;

    /** 提示词解析器。 */
    private final PromptResolver prompts;

    /**
     * 构造事实提取器。
     *
     * @param chatPort LLM 对话端口（null 时提取退化为空列表）
     * @param prompts  提示词解析器
     */
    public MemoryExtractor(ChatPort chatPort, PromptResolver prompts) {
        this.chatPort = chatPort;
        this.prompts = prompts;
    }

    /**
     * 提取候选记忆。
     *
     * @param transcript 对话段（仅用户侧文本，防回声回路）
     * @return 候选列表
     */
    public List<Candidate> extract(String transcript) {
        if (chatPort == null || !chatPort.available()
                || transcript == null || transcript.isBlank()) {
            return List.of();
        }
        String output = chatPort.chatJson(prompts.resolve(PromptKey.FACT_EXTRACT),
                "CONVERSATION SEGMENT:\n" + transcript);
        return parse(output);
    }

    /**
     * 解析 LLM 输出（容忍围栏与噪声；解析失败返回空列表，不阻断巩固）。
     *
     * @param output 模型输出
     * @return 候选列表
     */
    List<Candidate> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(LlmOutput.stripFences(output));
            // json_object 档根须为对象故提示词包壳 facts 字段，裸数组作降级档容错
            JsonNode items = root.isObject() ? root.get("facts") : root;
            if (items == null || !items.isArray()) {
                log.warn("事实提取输出无 facts 数组（丢弃）");
                return List.of();
            }
            List<Candidate> out = new ArrayList<>();
            for (JsonNode node : items) {
                String content = node.path("content").asText(null);
                if (content == null || content.isBlank()) {
                    continue;
                }
                out.add(new Candidate(
                        MemoryKind.parse(node.path("kind").asText(null)),
                        node.path("abstract").asText("").strip(),
                        content.strip(),
                        Math.clamp(node.path("importance").asInt(DEFAULT_IMPORTANCE),
                                MIN_IMPORTANCE, MAX_IMPORTANCE),
                        node.path("evidence").asText(null)));
            }
            return out;
        } catch (JsonProcessingException e) {
            log.warn("事实提取输出解析失败（丢弃）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 候选记忆。
     *
     * @param kind       类型
     * @param abstractText 一句话索引
     * @param content    正文
     * @param importance 重要度 1-10
     * @param evidence   证据
     */
    public record Candidate(MemoryKind kind, String abstractText, String content,
                            int importance, String evidence) {
    }
}
