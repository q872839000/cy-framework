// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.episodic;

import com.cy.orrery.memory.api.ChatPort;
import com.cy.orrery.memory.api.EmbeddingPort;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.ScopeType;
import com.cy.orrery.memory.api.VectorIndex;
import com.cy.orrery.memory.prompt.LlmOutput;
import com.cy.orrery.memory.prompt.PromptKey;
import com.cy.orrery.memory.prompt.PromptResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 情景记忆服务（L1 入口：经历卡片沉淀与召回）。
 * <p>
 * 沉淀：run 结束把本会话内容经 LLM 压成卡片（同 session 一张卡滚动更新），
 * 本体存 Redis（TTL 滑动续期），摘要向量进向量索引（kind=EPISODE）。
 * 召回：与 L2 同一条向量路，kinds 过滤 EPISODE，按 ID 回 Redis 取卡片。
 * </p>
 *
 * @author pcy
 */
public class EpisodeService {

    private static final Logger log = LoggerFactory.getLogger(EpisodeService.class);

    /** JSON 解析。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 卡片存储。 */
    private final EpisodeStore store;

    /** 向量索引。 */
    private final VectorIndex vectorIndex;

    /** 向量化端口。 */
    private final EmbeddingPort embeddingPort;

    /** LLM 对话端口。 */
    private final ChatPort chatPort;

    /** 提示词解析器。 */
    private final PromptResolver prompts;

    /** 时间源。 */
    private final Clock clock;

    /**
     * 构造情景记忆服务。
     *
     * @param store         卡片存储
     * @param vectorIndex   向量索引
     * @param embeddingPort 向量化端口
     * @param chatPort      LLM 对话端口（null 时沉淀跳过）
     * @param prompts       提示词解析器
     * @param clock         时间源（null 用 UTC）
     */
    public EpisodeService(EpisodeStore store, VectorIndex vectorIndex,
                          EmbeddingPort embeddingPort, ChatPort chatPort,
                          PromptResolver prompts, Clock clock) {
        this.store = store;
        this.vectorIndex = vectorIndex;
        this.embeddingPort = embeddingPort;
        this.chatPort = chatPort;
        this.prompts = prompts;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 沉淀/滚动更新情景卡片（run 结束异步调用；LLM 不可用时跳过向量沉淀）。
     *
     * @param sessionId  会话 ID
     * @param scope      归属（用户或 Agent）
     * @param transcript 本次会话内容（user/assistant 行）
     * @return 沉淀的卡片（LLM 不可用返回 null）
     */
    public EpisodeRecord settle(String sessionId, MemoryScope scope, String transcript) {
        if (chatPort == null || !chatPort.available()
                || transcript == null || transcript.isBlank()) {
            return null;
        }
        String output = chatPort.chatJson(prompts.resolve(PromptKey.EPISODE_SETTLE),
                "THIS SESSION:\n" + transcript);
        EpisodeRecord record = parse(sessionId, scope, output);
        if (record == null) {
            return null;
        }
        store.save(record);
        indexVector(record, scope);
        return record;
    }

    /**
     * 情景召回（"上次类似情况怎么做的"）。
     *
     * @param queryText 查询文本
     * @param scope     归属过滤
     * @param topK      返回上限
     * @return 命中卡片（按相似度降序，过期卡片自动跳过）
     */
    public List<EpisodeRecord> recall(String queryText, MemoryScope scope, int topK) {
        float[] vector = embeddingPort.embed(queryText);
        List<VectorIndex.VectorHit> hits = vectorIndex.search(vector, MemoryQuery.builder()
                        .tenantId(scope.tenantId()).scopeType(scope.type()).ownerId(scope.ownerId())
                        .kinds(Set.of(MemoryKind.EPISODE))
                        .build(),
                topK * 2);
        List<EpisodeRecord> out = new ArrayList<>();
        for (VectorIndex.VectorHit hit : hits) {
            store.get(hit.memoryId()).ifPresent(out::add);
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    /**
     * 隐私擦除联动（按 session 维度删除卡片与向量）。
     *
     * @param sessionId 会话 ID
     */
    public void eraseBySession(String sessionId) {
        store.delete(sessionId);
        vectorIndex.delete(sessionId);
    }

    /** 向量索引写入（kind=EPISODE，scope 为属主，召回按属主过滤）。 */
    private void indexVector(EpisodeRecord record, MemoryScope scope) {
        float[] vector = embeddingPort.embed(record.getTitle() + "\n" + record.getSummary());
        vectorIndex.upsert(record.getEpisodeId(), vector, MemoryItem.builder()
                .memoryId(record.getEpisodeId())
                .scope(scope)
                .kind(MemoryKind.EPISODE)
                .content(record.getSummary())
                .status(MemoryStatus.ACTIVE)
                .build());
    }

    /** 解析 LLM 卡片输出（失败返回 null，不阻断巩固）。 */
    private EpisodeRecord parse(String sessionId, MemoryScope scope, String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(LlmOutput.stripFences(output));
            String title = node.path("title").asText("").strip();
            String summary = node.path("summary").asText("").strip();
            if (summary.isEmpty()) {
                return null;
            }
            return EpisodeRecord.builder()
                    .episodeId(sessionId)
                    .sessionId(sessionId)
                    .tenantId(scope.tenantId())
                    .userId(scope.type() == ScopeType.USER ? scope.ownerId() : null)
                    .agentId(scope.type() == ScopeType.AGENT ? scope.ownerId() : null)
                    .title(title)
                    .summary(summary)
                    .outcome(node.path("outcome").asText(null))
                    .entities(textList(node.path("entities")))
                    .lessons(textList(node.path("lessons")))
                    .updatedAt(clock.instant())
                    .build();
        } catch (JsonProcessingException e) {
            log.warn("情景卡片输出解析失败（跳过本轮沉淀）: {}", e.getMessage());
            return null;
        }
    }

    /** JSON 数组 → 字符串列表。 */
    private List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }
}
