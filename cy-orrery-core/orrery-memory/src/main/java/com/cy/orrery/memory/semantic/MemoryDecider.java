// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.orrery.memory.api.ChatPort;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.prompt.LlmOutput;
import com.cy.orrery.memory.prompt.PromptKey;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 写入决策器（巩固管线第三步：候选 × 近邻 → ADD/UPDATE/DELETE/NOOP）。
 * <p>
 * 一次决策可返回多个操作（例如"候选推翻旧记忆"= DELETE 旧 + ADD 新），
 * 数组按序整体生效。解析失败的元素丢弃；整体解析失败按空列表处理——
 * 漏记的害处小于误改。
 * </p>
 *
 * @author pcy
 */
public class MemoryDecider {

    private static final Logger log = LoggerFactory.getLogger(MemoryDecider.class);

    /** JSON 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** LLM 对话端口。 */
    private final ChatPort chatPort;

    /** 提示词解析器。 */
    private final PromptResolver prompts;

    /**
     * 构造写入决策器。
     *
     * @param chatPort LLM 对话端口（null 时决策退化为空列表）
     * @param prompts  提示词解析器
     */
    public MemoryDecider(ChatPort chatPort, PromptResolver prompts) {
        this.chatPort = chatPort;
        this.prompts = prompts;
    }

    /**
     * 决策结果。
     */
    public enum Decision {
        /** 新增。 */
        ADD,
        /** 更新近邻。 */
        UPDATE,
        /** 作废近邻。 */
        DELETE,
        /** 跳过。 */
        NOOP
    }

    /**
     * 单条候选决策（可含多个操作，按输出顺序执行）。
     *
     * @param candidate 候选记忆
     * @param neighbors 近邻（相似度降序）
     * @return 操作列表（空 = 无动作）
     */
    public List<DecisionOutcome> decide(MemoryExtractor.Candidate candidate,
                                        List<MemoryItem> neighbors) {
        if (chatPort == null || !chatPort.available()) {
            return List.of();
        }
        StringBuilder nb = new StringBuilder();
        for (MemoryItem n : neighbors) {
            nb.append("- id=").append(n.getMemoryId())
                    .append(" | ").append(n.getContent()).append('\n');
        }
        String user = "CANDIDATE:\n" + candidate.content()
                + "\nNEAR-NEIGHBOR MEMORIES:\n" + (neighbors.isEmpty() ? "(none)" : nb.toString());
        String output = chatPort.chatJson(prompts.resolve(PromptKey.WRITE_DECISION), user);
        return parse(output);
    }

    /**
     * 解析决策输出（数组整体解析；非法元素丢弃；整体失败按空列表）。
     *
     * @param output 模型输出
     * @return 操作列表（NOOP 元素会被过滤，调用方只看到可执行操作）
     */
    List<DecisionOutcome> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(LlmOutput.stripFences(output));
            List<JsonNode> nodes = new ArrayList<>();
            // json_object 档根须为对象故提示词包壳 operations 字段，裸数组/单对象作降级档容错
            if (root.isObject() && root.path("operations").isArray()) {
                root.path("operations").forEach(nodes::add);
            } else if (root.isArray()) {
                root.forEach(nodes::add);
            } else {
                nodes.add(root);
            }
            List<DecisionOutcome> out = new ArrayList<>();
            for (JsonNode node : nodes) {
                Decision op;
                try {
                    op = Decision.valueOf(node.path("op").asText("NOOP")
                            .strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (op == Decision.NOOP) {
                    continue;
                }
                out.add(new DecisionOutcome(op,
                        node.path("targetId").asText(null),
                        node.path("content").asText(null)));
            }
            return out;
        } catch (JsonProcessingException e) {
            log.warn("写入决策输出解析失败（按无动作）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 决策输出。
     *
     * @param decision      操作
     * @param targetId      UPDATE/DELETE 目标记忆 ID
     * @param mergedContent UPDATE 合并后正文
     */
    public record DecisionOutcome(Decision decision, String targetId, String mergedContent) {
    }
}
