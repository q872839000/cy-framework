// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.EmbeddingPort;
import com.cy.orrery.memory.api.MemoryEventListener;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryKind;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryRevision;
import com.cy.orrery.memory.api.MemorySanitizer;
import com.cy.orrery.memory.api.MemoryScope;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.MemoryStore;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.api.VectorIndex;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 长期语义记忆服务（L2 唯一入口）。
 * <p>
 * 召回只有一条路：向量语义召回（query → embedding → 向量索引 topN →
 * 按 id 回本体存储 → 四维公式重排）。不做关键词模糊查询、不做降级兜底——
 * embedding 或向量索引缺失在启动期拒绝装配，运行期故障向上抛错。
 * 写入双通道：热路径 {@link #remember}（同步直写）与巩固通道
 * （{@link ConsolidationService} 提取-决策管线）。
 * </p>
 *
 * @author pcy
 */
public class SemanticMemoryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticMemoryService.class);

    /** 审计快照脱敏占位。 */
    private static final String REDACTED_SNAPSHOT = "{\"redacted\":true}";

    /** 一天的毫秒数（新鲜度衰减的年龄换算）。 */
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    /** 使用频率归一化底数（log1p(accessCount) / log1p(N)，访问 N 次即饱和）。 */
    private static final double USAGE_SATURATION_COUNT = 100.0;

    /** 重要度量纲（1-10 制，重排时归一化到 0-1）。 */
    private static final double IMPORTANCE_SCALE = 10.0;

    /** 默认重要度（未显式指定时的中位值；LLM 提取缺省值同源引用，见 {@link MemoryExtractor}）。 */
    static final int DEFAULT_IMPORTANCE = 5;

    /** 索引（abstract）最大长度（超出截断）。 */
    private static final int ABSTRACT_MAX_LENGTH = 50;

    /** 召回事件无属主过滤时的占位属主（仅事件观测，不参与存储）。 */
    private static final String UNFILTERED_OWNER = "-";

    /** 本体存储。 */
    private final MemoryStore store;

    /** 向量索引。 */
    private final VectorIndex vectorIndex;

    /** 向量化端口。 */
    private final EmbeddingPort embeddingPort;

    /** 召回配置。 */
    private final RetrievalConfig retrievalConfig;

    /** 配额（每 scope ACTIVE 上限；≤0 不限）。 */
    private final int quotaPerScope;

    /** ID 发号器。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 敏感过滤（null = 不过滤）。 */
    private final MemorySanitizer sanitizer;

    /** 事件监听。 */
    private final List<MemoryEventListener> listeners;

    /** 时间源。 */
    private final Clock clock;

    /**
     * 聚合依赖（构造参数封装载体）。
     *
     * @param store           本体存储
     * @param vectorIndex     向量索引
     * @param embeddingPort   向量化端口
     * @param retrievalConfig 召回配置
     * @param quotaPerScope   每 scope 配额
     * @param idGenerator     发号器
     * @param sanitizer       敏感过滤（可空）
     * @param listeners       事件监听
     * @param clock           时间源
     */
    public record Dependencies(MemoryStore store, VectorIndex vectorIndex,
                               EmbeddingPort embeddingPort, RetrievalConfig retrievalConfig,
                               int quotaPerScope, SnowflakeIdGenerator idGenerator,
                               MemorySanitizer sanitizer, List<MemoryEventListener> listeners,
                               Clock clock) {
    }

    /**
     * 构造语义记忆服务（null 组件回落默认：无召回配置用缺省权重、无监听为空表、无时间源用 UTC）。
     *
     * @param deps 聚合依赖
     */
    public SemanticMemoryService(Dependencies deps) {
        this.store = deps.store();
        this.vectorIndex = deps.vectorIndex();
        this.embeddingPort = deps.embeddingPort();
        this.retrievalConfig = deps.retrievalConfig() == null
                ? RetrievalConfig.defaults() : deps.retrievalConfig();
        this.quotaPerScope = deps.quotaPerScope();
        this.idGenerator = deps.idGenerator();
        this.sanitizer = deps.sanitizer();
        this.listeners = deps.listeners() == null ? List.of() : List.copyOf(deps.listeners());
        this.clock = deps.clock() == null ? Clock.systemUTC() : deps.clock();
    }

    // ==================== 写入（热路径） ====================

    /**
     * 写入记忆（同步直写：敏感闸口 + 默认值 + 审计 + 向量索引 + 配额）。
     *
     * @param item 条目（memoryId 可空，自动生成）
     * @return 落库后的条目
     * @throws CYException 敏感内容拒写（50607）/ 归属缺失（50609）
     */
    public MemoryItem remember(MemoryItem item) {
        if (item == null || item.getScope() == null) {
            throw new CYException(50609, item == null ? "item=null" : "scope=null");
        }
        if (item.getContent() == null || item.getContent().isBlank()) {
            throw new CYException(50606);
        }
        if (sanitizer != null) {
            sanitizer.assertClean(item.getContent());
            if (item.getAbstractText() != null) {
                sanitizer.assertClean(item.getAbstractText());
            }
        }
        Instant now = clock.instant();
        MemoryItem toSave = item.copy();
        if (toSave.getMemoryId() == null || toSave.getMemoryId().isBlank()) {
            toSave.setMemoryId(idGenerator.nextId());
        }
        if (toSave.getKind() == null) {
            toSave.setKind(MemoryKind.FACT);
        }
        if (toSave.getAbstractText() == null || toSave.getAbstractText().isBlank()) {
            toSave.setAbstractText(deriveAbstract(toSave.getContent()));
        }
        if (toSave.getStatus() == null) {
            toSave.setStatus(MemoryStatus.ACTIVE);
        }
        if (toSave.getImportance() <= 0) {
            toSave.setImportance(DEFAULT_IMPORTANCE);
        }
        toSave.setVersion(1);
        toSave.setCreatedAt(now);
        toSave.setUpdatedAt(now);

        MemoryStore target = store;
        target.inTransaction(() -> {
            target.save(toSave);
            target.appendRevision(revision(toSave, null, toSave, MemoryRevision.Op.CREATE,
                    actorOf(toSave.getProvenance()), reasonOf(toSave.getProvenance())));
            return null;
        });
        indexVector(toSave);
        fireWritten(toSave);
        enforceQuota(toSave.getScope());
        return toSave;
    }

    /**
     * 更正记忆内容（乐观锁 + 审计 + 向量重写）。
     * <p>
     * abstract 一致性约定：abstract 是 content 的一句话索引，正文被更正而
     * 未显式给新索引时，按写入路径同一规则从正文重派生——禁止出现
     * "正文已改、索引还是旧值"的腐烂状态（召回行与工具回读都引用 abstract）。
     * </p>
     *
     * @param memoryId    记忆 ID
     * @param newContent  新正文（null 不变）
     * @param newAbstract 新索引（null 且正文变更时按正文重派生）
     * @param provenance  本次变更溯源
     * @return 更新后的条目
     * @throws CYException 不存在（50602）/ 版本冲突（50603）/ 敏感拒写（50607）
     */
    public MemoryItem correct(String memoryId, String newContent, String newAbstract,
                              Provenance provenance) {
        MemoryItem before = mustGet(memoryId);
        MemoryItem after = before.copy();
        if (newContent != null) {
            if (sanitizer != null) {
                sanitizer.assertClean(newContent);
            }
            after.setContent(newContent);
        }
        if (newAbstract != null) {
            after.setAbstractText(newAbstract);
        } else if (newContent != null) {
            after.setAbstractText(deriveAbstract(newContent));
        }
        after.setUpdatedAt(clock.instant());
        after.setVersion(before.getVersion() + 1);
        if (provenance != null) {
            after.setProvenance(provenance);
        }
        MemoryItem applied = store.inTransaction(() -> {
            if (!store.update(after, before.getVersion())) {
                throw new CYException(50603, memoryId, before.getVersion());
            }
            store.appendRevision(revision(after, before, after, MemoryRevision.Op.UPDATE,
                    actorOf(provenance), reasonOf(provenance)));
            return after;
        });
        indexVector(applied);
        return applied;
    }

    /**
     * 标记被取代（作废不删除，向量同步摘除）。
     *
     * @param memoryId 记忆 ID
     * @param reason   原因
     * @return 更新后的条目
     */
    public MemoryItem supersede(String memoryId, String reason) {
        return transition(memoryId, MemoryStatus.SUPERSEDED, reason);
    }

    /**
     * 作废（被证伪或显式作废，向量同步摘除）。
     *
     * @param memoryId 记忆 ID
     * @param reason   作废理由
     * @return 更新后的条目
     */
    public MemoryItem invalidate(String memoryId, String reason) {
        return transition(memoryId, MemoryStatus.INVALIDATED, reason);
    }

    /**
     * 隐私擦除（物理删除本体 + 向量；审计链只留脱敏锚点）。
     *
     * @param memoryId 记忆 ID
     * @param actorId  操作者
     * @param reason   擦除依据
     */
    public void erase(String memoryId, String actorId, String reason) {
        MemoryItem victim = store.get(memoryId)
                .orElseThrow(() -> new CYException(50602, memoryId));
        store.inTransaction(() -> {
            store.purgeRevisions(memoryId);
            store.appendRevision(MemoryRevision.builder()
                    .revisionId(idGenerator.nextId()).memoryId(memoryId)
                    .op(MemoryRevision.Op.ERASE)
                    .beforeJson(REDACTED_SNAPSHOT).afterJson(null)
                    .actorId(actorId).reason(reason).at(clock.instant())
                    .build());
            store.delete(memoryId);
            return null;
        });
        vectorIndex.delete(memoryId);
        log.info("记忆已擦除: memoryId={}, actor={}", memoryId, actorId);
    }

    // ==================== 召回（向量唯一通道） ====================

    /**
     * 语义召回（向量检索 + 四维重排；命中回馈"用进废退"）。
     * <p>
     * 未显式指定 kinds 时限定 L2 三类型（FACT/PREFERENCE/EXPERIENCE），
     * L1 情景卡片（EPISODE）不进入长期记忆召回。
     * </p>
     *
     * @param queryText 查询文本
     * @param filter    scope/结构化过滤
     * @return 重排后的命中（score 降序）
     */
    public List<ScoredMemory> recall(String queryText, MemoryQuery filter) {
        long start = System.currentTimeMillis();
        int topK = filter != null && filter.getLimit() > 0
                ? filter.effectiveLimit() : retrievalConfig.topK();
        MemoryQuery safeFilter = filter == null ? MemoryQuery.builder().build() : filter;
        if (safeFilter.getKinds() == null || safeFilter.getKinds().isEmpty()) {
            safeFilter = MemoryQuery.builder()
                    .tenantId(safeFilter.getTenantId()).scopeType(safeFilter.getScopeType())
                    .ownerId(safeFilter.getOwnerId()).statuses(safeFilter.getStatuses())
                    .minImportance(safeFilter.getMinImportance()).limit(safeFilter.getLimit())
                    .kinds(Set.of(MemoryKind.FACT, MemoryKind.PREFERENCE,
                            MemoryKind.EXPERIENCE))
                    .build();
        }
        float[] queryVector = embeddingPort.embed(queryText);
        List<VectorIndex.VectorHit> hits = vectorIndex.search(queryVector, safeFilter, topK * 2);

        Map<String, MemoryItem> entries = new HashMap<>();
        for (VectorIndex.VectorHit hit : hits) {
            store.get(hit.memoryId()).ifPresent(e -> entries.put(hit.memoryId(), e));
        }
        Instant now = clock.instant();
        List<ScoredMemory> scored = new ArrayList<>();
        for (VectorIndex.VectorHit hit : hits) {
            MemoryItem entry = entries.get(hit.memoryId());
            if (entry == null || !safeFilter.effectiveStatuses().contains(entry.getStatus())) {
                continue;
            }
            scored.add(new ScoredMemory(entry, score(entry, hit.similarity(), now)));
        }
        scored.sort(Comparator.comparingDouble(ScoredMemory::score).reversed());
        List<ScoredMemory> result = scored.stream().limit(topK).toList();
        recordAccess(result.stream().map(s -> s.entry().getMemoryId()).toList());
        long elapsed = System.currentTimeMillis() - start;
        for (MemoryEventListener l : listeners) {
            try {
                l.onRecalled(safeFilter.getScopeType() == null ? null
                        : new MemoryScope(safeFilter.getScopeType(),
                                safeFilter.getOwnerId() == null ? UNFILTERED_OWNER
                                        : safeFilter.getOwnerId(),
                                safeFilter.getTenantId()), result.size(), elapsed);
            } catch (RuntimeException e) {
                log.debug("记忆事件监听器异常（忽略）: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * 重排公式（相似度/重要度/新鲜度/使用频率）。
     *
     * @param entry      条目
     * @param similarity 相似度
     * @param now        当前时刻
     * @return 得分
     */
    public double score(MemoryItem entry, double similarity, Instant now) {
        double ageDays = entry.getCreatedAt() == null ? 0
                : Math.max(0, (now.toEpochMilli() - entry.getCreatedAt().toEpochMilli()) / MILLIS_PER_DAY);
        double recency = Math.exp(-ageDays / Math.max(1, retrievalConfig.recencyHalfLifeDays()));
        double usage = Math.log1p(Math.max(0, entry.getAccessCount())) / Math.log1p(USAGE_SATURATION_COUNT);
        return retrievalConfig.similarityWeight() * similarity
                + retrievalConfig.importanceWeight() * (entry.getImportance() / IMPORTANCE_SCALE)
                + retrievalConfig.recencyWeight() * recency
                + retrievalConfig.usageWeight() * usage;
    }

    /**
     * 结构化读取（管理端/内部用；非召回通道）。
     *
     * @param memoryId 记忆 ID
     * @return 条目
     */
    public Optional<MemoryItem> get(String memoryId) {
        return store.get(memoryId);
    }

    /**
     * 结构化过滤查询（管理端/内部用；非召回通道）。
     *
     * @param query 查询条件
     * @return 条目列表
     */
    public List<MemoryItem> list(MemoryQuery query) {
        return store.recall(query);
    }

    /**
     * 审计链查询。
     *
     * @param memoryId 记忆 ID
     * @return 审计链
     */
    public List<MemoryRevision> history(String memoryId) {
        return store.history(memoryId);
    }

    /**
     * 命中回馈（accessCount+1；尽力而为，不写审计链）。
     * <p>
     * 走存储专用轻量路径：原子自增、不动 version——version 只随内容修订/
     * 状态迁移推进，访问统计不再抬高乐观锁版本（避免召回把并发更正顶出
     * 50603 假冲突）。
     * </p>
     *
     * @param memoryIds 记忆 ID 列表
     */
    public void recordAccess(List<String> memoryIds) {
        Instant now = clock.instant();
        for (String id : memoryIds) {
            try {
                store.recordAccess(id, now);
            } catch (RuntimeException e) {
                log.debug("访问计数更新失败（忽略）: {}", e.getMessage());
            }
        }
    }

    // ==================== 治理 ====================

    /**
     * 物理清理墓碑（非 ACTIVE 且 updatedAt 早于阈值）。
     *
     * @param olderThan 阈值时刻
     * @return 删除条数
     */
    public int purgeTombstoned(Instant olderThan) {
        return store.purgeTombstoned(olderThan);
    }

    /** 配额逐出（超配额按重要度升序、最久未访问逐出）。 */
    private void enforceQuota(MemoryScope scope) {
        if (quotaPerScope <= 0) {
            return;
        }
        long count = store.countActive(scope);
        if (count <= quotaPerScope) {
            return;
        }
        List<MemoryItem> active = new ArrayList<>(store.recall(MemoryQuery.builder()
                .tenantId(scope.tenantId()).scopeType(scope.type()).ownerId(scope.ownerId())
                .statuses(Set.of(MemoryStatus.ACTIVE))
                .limit((int) Math.min(count, MemoryQuery.MAX_LIMIT))
                .build()));
        active.sort(Comparator.comparingInt(MemoryItem::getImportance)
                .thenComparing(MemoryItem::getLastAccessedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())));
        int toEvict = (int) (count - quotaPerScope);
        for (int i = 0; i < toEvict && i < active.size(); i++) {
            try {
                supersede(active.get(i).getMemoryId(), "配额逐出");
            } catch (RuntimeException e) {
                log.warn("配额逐出失败（跳过）: memoryId={}", active.get(i).getMemoryId());
            }
        }
    }

    /** 状态迁移（乐观锁 + 审计 + 向量摘除）。 */
    private MemoryItem transition(String memoryId, MemoryStatus target, String reason) {
        MemoryItem before = mustGet(memoryId);
        MemoryItem after = before.copy();
        after.setStatus(target);
        after.setUpdatedAt(clock.instant());
        after.setVersion(before.getVersion() + 1);
        MemoryItem applied = store.inTransaction(() -> {
            if (!store.update(after, before.getVersion())) {
                throw new CYException(50603, memoryId, before.getVersion());
            }
            store.appendRevision(revision(after, before, after,
                    target == MemoryStatus.SUPERSEDED
                            ? MemoryRevision.Op.SUPERSEDE : MemoryRevision.Op.INVALIDATE,
                    actorOf(before.getProvenance()), reason));
            return after;
        });
        vectorIndex.delete(memoryId);
        return applied;
    }

    /** 向量索引写入（embedding 正文）。 */
    private void indexVector(MemoryItem item) {
        float[] vector = embeddingPort.embed(item.getContent());
        vectorIndex.upsert(item.getMemoryId(), vector, item);
    }

    /** 审计修订构造。 */
    private MemoryRevision revision(MemoryItem item, MemoryItem before, MemoryItem after,
                                    MemoryRevision.Op op, String actorId, String reason) {
        return MemoryRevision.builder()
                .revisionId(idGenerator.nextId())
                .memoryId(item.getMemoryId())
                .op(op)
                .beforeJson(before == null ? null : before.getContent())
                .afterJson(after == null ? null : after.getContent())
                .actorId(actorId).reason(reason).at(clock.instant())
                .build();
    }

    private MemoryItem mustGet(String memoryId) {
        return store.get(memoryId).orElseThrow(() -> new CYException(50602, memoryId));
    }

    /** 索引派生（写入/更正共用同一规则：超出 {@link #ABSTRACT_MAX_LENGTH} 截断）。 */
    private static String deriveAbstract(String content) {
        return content.length() <= ABSTRACT_MAX_LENGTH ? content
                : content.substring(0, ABSTRACT_MAX_LENGTH);
    }

    private void fireWritten(MemoryItem item) {
        for (MemoryEventListener l : listeners) {
            try {
                l.onMemoryWritten(item);
            } catch (RuntimeException e) {
                log.debug("记忆事件监听器异常（忽略）: {}", e.getMessage());
            }
        }
    }

    private String actorOf(Provenance p) {
        return p == null ? null : p.actorId();
    }

    private String reasonOf(Provenance p) {
        return p == null ? null : p.reason();
    }

    /**
     * 一条重排后的命中。
     *
     * @param entry 记忆本体
     * @param score 重排得分
     */
    public record ScoredMemory(MemoryItem entry, double score) {
    }
}
