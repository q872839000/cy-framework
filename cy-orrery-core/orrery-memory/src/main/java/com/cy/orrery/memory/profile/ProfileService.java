// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.support.SnowflakeIdGenerator;
import com.cy.orrery.memory.context.TokenEstimator;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 画像服务（生成 / 存储 / 召回的唯一入口）。
 * <p>
 * 维度定义权在消费方（{@link ProfileDimensionProvider}）：组件不定维度、
 * 不定触发时机——消费方在自己选定的时机调 {@link #extractAndUpdate}。
 * 召回两路：整档注入 {@link #render}（默认）与按维查询 {@link #getItems}。
 * </p>
 *
 * @author pcy
 */
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    /** 画像存储。 */
    private final ProfileStore store;

    /** 维度注册表（key → 定义；启动期由全部 Provider 合并，key 重复启动拒绝）。 */
    private final Map<String, ProfileDimension> dimensions;

    /** 画像提取器（null = 无 LLM，extractAndUpdate 退化为空操作）。 */
    private final ProfileExtractor extractor;

    /** 仲裁器。 */
    private final ProfileArbiter arbiter;

    /** ID 发号器。 */
    private final SnowflakeIdGenerator idGenerator;

    /** 时间源。 */
    private final Clock clock;

    /**
     * 构造画像服务（启动期合并全部 Provider 的维度定义，key 重复启动拒绝）。
     *
     * @param store       画像存储
     * @param providers   维度定义供给（消费方注册；可空）
     * @param extractor   画像提取器（null 时 extractAndUpdate 退化为空操作）
     * @param idGenerator ID 发号器
     * @param clock       时间源
     */
    public ProfileService(ProfileStore store, List<ProfileDimensionProvider> providers,
                          ProfileExtractor extractor, SnowflakeIdGenerator idGenerator,
                          Clock clock) {
        this.store = store;
        this.dimensions = mergeDimensions(providers);
        this.extractor = extractor;
        this.arbiter = new ProfileArbiter(clock);
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 合并全部 Provider 的维度定义。
     *
     * @throws CYException 维度 key 重复（50625）
     */
    private Map<String, ProfileDimension> mergeDimensions(List<ProfileDimensionProvider> providers) {
        Map<String, ProfileDimension> merged = new LinkedHashMap<>();
        for (ProfileDimensionProvider provider : providers == null ? List.<ProfileDimensionProvider>of() : providers) {
            for (ProfileDimension d : provider.dimensions()) {
                if (merged.putIfAbsent(d.key(), d) != null) {
                    throw new CYException(50625, d.key());
                }
            }
        }
        return Map.copyOf(merged);
    }

    /**
     * 已注册维度（管理端/调试用）。
     *
     * @return 维度列表
     */
    public List<ProfileDimension> dimensions() {
        return List.copyOf(dimensions.values());
    }

    // ==================== 生成 ====================

    /**
     * 提取并更新画像（消费方自选触发时机：run 结束异步 / 显式指令即时 / 管理端手动）。
     * <p>
     * 矛盾裁决：当前画像以 "[条目ID] 维度: 值" 形态喂给提取器；新条目声明
     * {@code supersedes} 回指被推翻的旧条目时，先确定性取代旧条目（显式新陈述
     * 优先于既有画像），再走维度仲裁入库——同一主体的新旧说法不允许同时 ACTIVE。
     * </p>
     *
     * @param ownerType  属主类型
     * @param tenantId   租户 ID（可空）
     * @param ownerId    属主 ID
     * @param transcript 对话段（证据唯一来源）
     * @return 本次新入库的条目数
     */
    public int extractAndUpdate(ProfileItem.OwnerType ownerType, String tenantId, String ownerId,
                                String transcript) {
        if (extractor == null || dimensions.isEmpty()) {
            return 0;
        }
        String current = renderForExtraction(ownerType, tenantId, ownerId);
        List<ProfileExtractor.CandidateEntry> candidates = extractor.extract(
                List.copyOf(dimensions.values()), transcript, current);
        int saved = 0;
        for (ProfileExtractor.CandidateEntry candidate : candidates) {
            ProfileDimension dimension = dimensions.get(candidate.dimension());
            if (dimension == null) {
                log.debug("画像提取输出未注册维度（丢弃）: dimension={}", candidate.dimension());
                continue;
            }
            applySupersedes(ownerType, tenantId, ownerId, candidate.supersedes());
            ProfileItem incoming = ProfileItem.builder()
                    .itemId(idGenerator.nextId())
                    .ownerType(ownerType).ownerId(ownerId).tenantId(tenantId)
                    .dimension(candidate.dimension())
                    .value(candidate.value())
                    .inferred(candidate.inferred())
                    .evidence(candidate.evidence())
                    .build();
            List<ProfileItem> existing = store.list(ownerType, tenantId, ownerId,
                    candidate.dimension(), false);
            if (arbiter.apply(store, arbiter.arbitrate(dimension, incoming, existing),
                    incoming) != null) {
                saved++;
            }
        }
        return saved;
    }

    /**
     * 提取器参照用的当前画像文本（每条带条目 ID，供矛盾回指）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @return 画像文本（空画像返回空串）
     */
    private String renderForExtraction(ProfileItem.OwnerType ownerType, String tenantId,
                                       String ownerId) {
        List<ProfileItem> items = store.list(ownerType, tenantId, ownerId, null, false);
        StringBuilder sb = new StringBuilder();
        for (ProfileItem item : items) {
            sb.append('[').append(item.getItemId()).append("] ")
                    .append(item.getDimension()).append(": ").append(item.getValue())
                    .append(item.isInferred() ? " (inferred)" : "")
                    .append('\n');
        }
        return sb.toString().strip();
    }

    /**
     * 应用矛盾回指（仅允许取代同属主、当前 ACTIVE 的条目，防越权误伤）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param itemIds   被推翻的条目 ID 列表
     */
    private void applySupersedes(ProfileItem.OwnerType ownerType, String tenantId, String ownerId,
                                 List<String> itemIds) {
        if (itemIds == null) {
            return;
        }
        for (String itemId : itemIds) {
            try {
                var found = store.get(itemId);
                if (found.isEmpty()) {
                    continue;
                }
                ProfileItem old = found.get();
                if (old.getOwnerType() != ownerType || !old.getOwnerId().equals(ownerId)
                        || !Objects.equals(old.getTenantId(), tenantId)
                        || old.getStatus() != ProfileItem.Status.ACTIVE) {
                    continue;
                }
                ProfileItem after = old.copy();
                after.setStatus(ProfileItem.Status.SUPERSEDED);
                after.setUpdatedAt(clock.instant());
                after.setVersion(old.getVersion() + 1);
                if (!store.update(after, old.getVersion())) {
                    log.warn("画像矛盾取代版本冲突（跳过）: itemId={}", itemId);
                }
            } catch (RuntimeException e) {
                log.warn("画像矛盾取代失败（跳过）: itemId={}", itemId, e);
            }
        }
    }

    /**
     * 显式写入一条画像（"记住我…"直写路径；维度必须已注册）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param dimension 维度 key
     * @param value     值
     * @param evidence  证据（可空）
     * @return 入库条目（被仲裁拒绝返回 null）
     * @throws CYException 维度未注册（50624）
     */
    public ProfileItem addExplicit(ProfileItem.OwnerType ownerType, String tenantId,
                                   String ownerId, String dimension, String value,
                                   String evidence) {
        ProfileDimension def = dimensions.get(dimension);
        if (def == null) {
            throw new CYException(50624, dimension);
        }
        if (value == null || value.isBlank()) {
            throw new CYException(50621);
        }
        ProfileItem incoming = ProfileItem.builder()
                .itemId(idGenerator.nextId())
                .ownerType(ownerType).ownerId(ownerId).tenantId(tenantId)
                .dimension(dimension).value(value.strip())
                .inferred(false).evidence(evidence)
                .build();
        List<ProfileItem> existing = store.list(ownerType, tenantId, ownerId, dimension, false);
        return arbiter.apply(store, arbiter.arbitrate(def, incoming, existing), incoming);
    }

    // ==================== 召回 ====================

    /**
     * 整档渲染注入行（按维度注册顺序；预算内截断，0 = 不限预算）。
     *
     * @param ownerType   属主类型
     * @param tenantId    租户 ID（可空）
     * @param ownerId     属主 ID
     * @param tokenBudget token 预算（≤0 不限）
     * @return 注入行（空画像返回空列表）
     */
    public List<String> render(ProfileItem.OwnerType ownerType, String tenantId, String ownerId,
                               int tokenBudget) {
        List<ProfileItem> items = store.list(ownerType, tenantId, ownerId, null, false);
        if (items.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> byDimension = new LinkedHashMap<>();
        for (ProfileItem item : items) {
            byDimension.computeIfAbsent(item.getDimension(), k -> new ArrayList<>())
                    .add(item.getValue());
        }
        List<String> lines = new ArrayList<>();
        int used = 0;
        for (Map.Entry<String, List<String>> e : byDimension.entrySet()) {
            ProfileDimension def = dimensions.get(e.getKey());
            String label = def == null ? e.getKey() : def.label();
            String line = label + "：" + String.join("；", e.getValue());
            int tokens = TokenEstimator.estimate(line);
            if (tokenBudget > 0 && used + tokens > tokenBudget) {
                break;
            }
            lines.add(line);
            used += tokens;
        }
        return lines;
    }

    /**
     * 按维查询（Agent 在特定决策点主动查；dimension 为 null 返回全部 ACTIVE）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param dimension 维度 key（null = 全部）
     * @return ACTIVE 条目
     */
    public List<ProfileItem> getItems(ProfileItem.OwnerType ownerType, String tenantId,
                                      String ownerId, String dimension) {
        return store.list(ownerType, tenantId, ownerId, dimension, false);
    }

    /**
     * 全状态查询（管理端透明展示用：含 SUPERSEDED/INVALIDATED，时间升序）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param dimension 维度 key（null = 全部）
     * @return 全部条目
     */
    public List<ProfileItem> listAll(ProfileItem.OwnerType ownerType, String tenantId,
                                     String ownerId, String dimension) {
        return store.list(ownerType, tenantId, ownerId, dimension, true);
    }

    /**
     * 显式作废一条画像条目。
     *
     * @param itemId 条目 ID
     * @throws CYException 条目不存在（50620）/ 版本冲突（50603）
     */
    public void invalidate(String itemId) {
        ProfileItem item = store.get(itemId).orElseThrow(() -> new CYException(50620, itemId));
        ProfileItem after = item.copy();
        after.setStatus(ProfileItem.Status.INVALIDATED);
        after.setUpdatedAt(clock.instant());
        after.setVersion(item.getVersion() + 1);
        if (!store.update(after, item.getVersion())) {
            throw new CYException(50603, itemId, item.getVersion());
        }
    }

    /**
     * 人工编辑画像条目（旧条目作废，同维度新值以显式来源生效）。
     *
     * @param itemId   条目 ID
     * @param newValue 新值
     * @param evidence 编辑依据（可空）
     * @return 新条目
     * @throws CYException 条目不存在（50620）
     */
    public ProfileItem edit(String itemId, String newValue, String evidence) {
        ProfileItem old = store.get(itemId).orElseThrow(() -> new CYException(50620, itemId));
        invalidate(itemId);
        return addExplicit(old.getOwnerType(), old.getTenantId(), old.getOwnerId(),
                old.getDimension(), newValue, evidence);
    }

    /**
     * 条目谱系（同属主同维度的全部条目含已作废，按时间升序——审计展示用）。
     *
     * @param itemId 条目 ID
     * @return 谱系列表
     * @throws CYException 条目不存在（50620）
     */
    public List<ProfileItem> lineage(String itemId) {
        ProfileItem item = store.get(itemId).orElseThrow(() -> new CYException(50620, itemId));
        return store.list(item.getOwnerType(), item.getTenantId(), item.getOwnerId(),
                item.getDimension(), true);
    }

    /**
     * 隐私擦除（物理删除属主全部条目）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @return 删除条数
     */
    public int erase(ProfileItem.OwnerType ownerType, String tenantId, String ownerId) {
        return store.deleteByOwner(ownerType, tenantId, ownerId);
    }
}
