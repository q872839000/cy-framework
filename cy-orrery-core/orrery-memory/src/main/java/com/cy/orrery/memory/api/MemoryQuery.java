// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * 记忆查询条件（scope 过滤 + 状态/类型过滤 + 限量）。
 * <p>
 * 仅承载结构化过滤；语义相似度由向量通道承担，不提供关键词字段。
 * </p>
 *
 * @author pcy
 */
@Value
@Builder
public class MemoryQuery {

    /** 租户过滤（null 不过滤）。 */
    String tenantId;

    /** 归属类型过滤（null 不过滤）。 */
    ScopeType scopeType;

    /** 属主过滤（null 不过滤；与 scopeType 组合定位）。 */
    String ownerId;

    /** 类型过滤（null/空 = 不限）。 */
    Set<MemoryKind> kinds;

    /** 状态过滤（null = 默认仅 ACTIVE）。 */
    Set<MemoryStatus> statuses;

    /** 最低重要度（null/≤0 不过滤）。 */
    Integer minImportance;

    /** 返回上限（≤0 用默认 100）。 */
    int limit;

    /** 默认上限。 */
    public static final int DEFAULT_LIMIT = 100;

    /** 硬上限（防全表扫描）。 */
    public static final int MAX_LIMIT = 1000;

    /**
     * 有效上限（约束在 1..MAX_LIMIT）。
     *
     * @return 上限
     */
    public int effectiveLimit() {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * 生效的状态集合（默认仅 ACTIVE）。
     *
     * @return 状态集合
     */
    public Set<MemoryStatus> effectiveStatuses() {
        return statuses == null || statuses.isEmpty() ? Set.of(MemoryStatus.ACTIVE) : statuses;
    }
}
