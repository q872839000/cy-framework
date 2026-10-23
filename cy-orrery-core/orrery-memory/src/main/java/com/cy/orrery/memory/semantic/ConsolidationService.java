// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.orrery.memory.api.EmbeddingPort;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.api.VectorIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 巩固管线（run 结束异步触发：提取 → embedding → 近邻比对 → 决策 → 执行）。
 * <p>
 * 单候选处理链：向量近邻 topN（同 scope）→ 相似度超去重阈值且文本归一化相等
 * 直接 NOOP（省 LLM 调用）→ 否则 LLM 决策（一次可输出多个操作，如矛盾裁决的
 * "作废旧记忆 + 新增"，按序经 {@link SemanticMemoryService} 的正式写入路径执行，
 * 审计/向量/配额一致）。
 * 热路径优先窗口：与窗口内热路径直写文本归一化相等的候选直接 NOOP
 * （防止巩固通道复活刚写入的内容）。
 * </p>
 *
 * @author pcy
 */
public class ConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationService.class);

    /** 近邻比对的召回条数。 */
    private static final int NEIGHBOR_COUNT = 10;

    /** 语义记忆服务。 */
    private final SemanticMemoryService memoryService;

    /** 事实提取器。 */
    private final MemoryExtractor extractor;

    /** 写入决策器。 */
    private final MemoryDecider decider;

    /** 向量索引（近邻比对）。 */
    private final VectorIndex vectorIndex;

    /** 向量化端口。 */
    private final EmbeddingPort embeddingPort;

    /** 去重相似度阈值。 */
    private final double dedupThreshold;

    /** 热路径优先窗口（秒）。 */
    private final long hotPathWindowSeconds;

    /** 时间源。 */
    private final Clock clock;

    /**
     * 聚合依赖。
     *
     * @param memoryService         语义记忆服务
     * @param extractor             事实提取器
     * @param decider               写入决策器
     * @param vectorIndex           向量索引
     * @param embeddingPort         向量化端口
     * @param dedupThreshold        去重相似度阈值
     * @param hotPathWindowSeconds  热路径优先窗口（秒）
     * @param clock                 时间源
     */
    public record Dependencies(SemanticMemoryService memoryService, MemoryExtractor extractor,
                               MemoryDecider decider, VectorIndex vectorIndex,
                               EmbeddingPort embeddingPort, double dedupThreshold,
                               long hotPathWindowSeconds, Clock clock) {
    }

    /**
     * 构造巩固管线。
     *
     * @param deps 聚合依赖
     */
    public ConsolidationService(Dependencies deps) {
        this.memoryService = deps.memoryService();
        this.extractor = deps.extractor();
        this.decider = deps.decider();
        this.vectorIndex = deps.vectorIndex();
        this.embeddingPort = deps.embeddingPort();
        this.dedupThreshold = deps.dedupThreshold();
        this.hotPathWindowSeconds = deps.hotPathWindowSeconds();
        this.clock = deps.clock() == null ? Clock.systemUTC() : deps.clock();
    }

    /**
     * 巩固一轮对话（同步执行；调用方负责异步化）。
     *
     * @param scope     目标归属
     * @param transcript 对话段（仅用户侧文本，防回声回路）
     * @param sourceRef 来源指针（sessionId#runId）
     * @return 实际新增/更新/作废的条数合计
     */
    public int consolidate(MemoryScope scope, String transcript, String sourceRef) {
        List<MemoryExtractor.Candidate> candidates = extractor.extract(transcript);
        if (candidates.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (MemoryExtractor.Candidate candidate : candidates) {
            try {
                applied += processCandidate(scope, candidate, sourceRef);
            } catch (RuntimeException e) {
                log.warn("巩固候选处理失败（跳过本条）: scope={}, content={}",
                        scope.ownerId(), abbreviate(candidate.content()), e);
            }
        }
        return applied;
    }

    /**
     * 处理单条候选。
     *
     * @return 实际生效的操作数（一次决策可含多个操作，如 推翻旧记忆+新增）
     */
    private int processCandidate(MemoryScope scope, MemoryExtractor.Candidate candidate,
                                 String sourceRef) {
        if (isHotPathDuplicate(scope, candidate.content())) {
            return 0;
        }
        float[] vector = embeddingPort.embed(candidate.content());
        List<VectorIndex.VectorHit> hits = vectorIndex.search(vector,
                MemoryQuery.builder()
                        .tenantId(scope.tenantId()).scopeType(scope.type()).ownerId(scope.ownerId())
                        .build(),
                NEIGHBOR_COUNT);
        List<MemoryItem> neighbors = new ArrayList<>();
        for (VectorIndex.VectorHit hit : hits) {
            memoryService.get(hit.memoryId()).ifPresent(neighbors::add);
        }
        // 相似度超阈值且文本归一化相等 → 直接 NOOP，不花 LLM 调用
        //（hits 按相似度降序，首个即最近邻）
        if (!hits.isEmpty() && hits.get(0).similarity() >= dedupThreshold && !neighbors.isEmpty()
                && normalized(neighbors.get(0).getContent()).equals(normalized(candidate.content()))) {
            return 0;
        }
        List<MemoryDecider.DecisionOutcome> outcomes = decider.decide(candidate, neighbors);
        int applied = 0;
        for (MemoryDecider.DecisionOutcome outcome : outcomes) {
            if (applyDecision(scope, candidate, outcome, sourceRef)) {
                applied++;
            }
        }
        return applied;
    }

    /** 决策执行（走语义记忆服务的正式写入路径）。 */
    private boolean applyDecision(MemoryScope scope, MemoryExtractor.Candidate candidate,
                                  MemoryDecider.DecisionOutcome outcome, String sourceRef) {
        switch (outcome.decision()) {
            case ADD -> {
                memoryService.remember(MemoryItem.builder()
                        .scope(scope).kind(candidate.kind())
                        .abstractText(candidate.abstractText())
                        .content(candidate.content())
                        .sourceRef(sourceRef)
                        .importance(candidate.importance())
                        .provenance(Provenance.extracted(sourceRef, "巩固通道提取"))
                        .build());
                return true;
            }
            case UPDATE -> {
                if (outcome.targetId() == null || outcome.mergedContent() == null) {
                    return false;
                }
                memoryService.correct(outcome.targetId(), outcome.mergedContent(), null,
                        Provenance.extracted(sourceRef, "巩固通道更新"));
                return true;
            }
            case DELETE -> {
                if (outcome.targetId() == null) {
                    return false;
                }
                memoryService.invalidate(outcome.targetId(), "巩固通道推翻: " + sourceRef);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 热路径优先窗口内的直写查重（防复活刚写入内容）。 */
    private boolean isHotPathDuplicate(MemoryScope scope, String content) {
        if (hotPathWindowSeconds <= 0) {
            return false;
        }
        Instant since = clock.instant().minus(Duration.ofSeconds(hotPathWindowSeconds));
        List<MemoryItem> recent = memoryService.list(MemoryQuery.builder()
                .tenantId(scope.tenantId()).scopeType(scope.type()).ownerId(scope.ownerId())
                .build());
        String target = normalized(content);
        for (MemoryItem item : recent) {
            if (item.getCreatedAt() != null && item.getCreatedAt().isAfter(since)
                    && normalized(item.getContent()).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 文本归一化（去空白、小写）。 */
    private static String normalized(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 日志用缩写。 */
    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }
}
