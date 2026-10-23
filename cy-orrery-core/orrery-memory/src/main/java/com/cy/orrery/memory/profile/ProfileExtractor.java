// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import com.cy.orrery.memory.api.ChatPort;
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
 * 画像提取器（维度段由消费方的维度定义动态拼装，LLM 只输出有证据的维度）。
 *
 * @author pcy
 */
public class ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractor.class);

    /** JSON 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** LLM 对话端口。 */
    private final ChatPort chatPort;

    /** 提示词解析器。 */
    private final PromptResolver prompts;

    /**
     * 构造画像提取器。
     *
     * @param chatPort LLM 对话端口（null 时提取退化为空列表）
     * @param prompts  提示词解析器
     */
    public ProfileExtractor(ChatPort chatPort, PromptResolver prompts) {
        this.chatPort = chatPort;
        this.prompts = prompts;
    }

    /**
     * 提取候选画像条目。
     *
     * @param dimensions     维度定义（动态拼装进提示词）
     * @param transcript     对话段
     * @param currentProfile 当前画像渲染文本（带条目 ID，供矛盾条目回指）
     * @return 候选条目（dimension/value/inferred/evidence/supersedes）
     */
    public List<CandidateEntry> extract(List<ProfileDimension> dimensions, String transcript,
                                        String currentProfile) {
        if (chatPort == null || !chatPort.available() || dimensions.isEmpty()
                || transcript == null || transcript.isBlank()) {
            return List.of();
        }
        String prompt = prompts.resolve(PromptKey.PROFILE_EXTRACT)
                .replace("{{dimensions}}", renderDimensions(dimensions));
        String user = "CONVERSATION SEGMENT:\n" + transcript
                + "\nCURRENT PROFILE:\n"
                + (currentProfile == null || currentProfile.isBlank() ? "(empty)" : currentProfile);
        String output = chatPort.chatJson(prompt, user);
        return parse(output);
    }

    /**
     * 维度段拼装（每条维度一段提取指令）。
     *
     * @param dimensions 维度定义
     * @return 提示词维度段
     */
    private String renderDimensions(List<ProfileDimension> dimensions) {
        StringBuilder sb = new StringBuilder();
        for (ProfileDimension d : dimensions) {
            sb.append("- ").append(d.key()).append(" (").append(d.label()).append("): ")
                    .append(d.extractionHint() == null ? "" : d.extractionHint())
                    .append(d.multiValue()
                            ? " multi-valued, at most " + d.maxItems() + " items."
                            : " single-valued.")
                    .append('\n');
        }
        return sb.toString();
    }

    /**
     * 解析 LLM 输出（容忍 markdown 围栏与前后噪声）。
     *
     * @param output 模型输出
     * @return 候选条目
     */
    List<CandidateEntry> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String json = stripFences(output);
        try {
            JsonNode root = objectMapper.readTree(json);
            // json_object 档根须为对象故提示词包壳 entries 字段，裸数组作降级档容错
            JsonNode items = root.isObject() ? root.get("entries") : root;
            if (items == null || !items.isArray()) {
                log.warn("画像提取输出无 entries 数组（丢弃）");
                return List.of();
            }
            List<CandidateEntry> out = new ArrayList<>();
            for (JsonNode node : items) {
                String dimension = node.path("dimension").asText(null);
                String value = node.path("value").asText(null);
                if (dimension == null || dimension.isBlank() || value == null || value.isBlank()) {
                    continue;
                }
                List<String> supersedes = new ArrayList<>();
                JsonNode supNode = node.path("supersedes");
                if (supNode.isArray()) {
                    for (JsonNode id : supNode) {
                        String itemId = id.asText(null);
                        if (itemId != null && !itemId.isBlank()) {
                            supersedes.add(itemId.strip());
                        }
                    }
                }
                out.add(new CandidateEntry(dimension.strip(), value.strip(),
                        node.path("inferred").asBoolean(false),
                        node.path("evidence").asText(null), List.copyOf(supersedes)));
            }
            return out;
        } catch (JsonProcessingException e) {
            log.warn("画像提取输出解析失败（丢弃）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 去掉 ```json 围栏与首尾空白。 */
    static String stripFences(String output) {
        return LlmOutput.stripFences(output);
    }

    /**
     * 提取候选条目。
     *
     * @param dimension  维度 key
     * @param value      值
     * @param inferred   是否推断
     * @param evidence   证据
     * @param supersedes 本条目推翻的既有条目 ID（矛盾裁决，可空）
     */
    public record CandidateEntry(String dimension, String value, boolean inferred,
                                 String evidence, List<String> supersedes) {
        /** 无矛盾回指的便捷构造。 */
        public CandidateEntry(String dimension, String value, boolean inferred, String evidence) {
            this(dimension, value, inferred, evidence, List.of());
        }
    }
}
