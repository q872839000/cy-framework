// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 长期记忆条目（L2 语义记忆本体）。
 * <p>
 * 三级抽象：{@code abstractText}（一句话索引，召回候选与注入的廉价代表）
 * / {@code content}（记忆正文，命中后注入）/ {@code sourceRef}（来源指针，
 * 原文按需回查，不进默认注入）。
 * </p>
 *
 * @author pcy
 */
@Data
@Builder
@AllArgsConstructor
public class MemoryItem {

    /** 记忆 ID（雪花，缺省自动生成）。 */
    private String memoryId;

    /** 归属范围（必填）。 */
    private MemoryScope scope;

    /** 内容类型（缺省 FACT）。 */
    private MemoryKind kind;

    /** 一句话索引（≤50 字；缺省时取 content 截断）。 */
    private String abstractText;

    /** 记忆正文（必填，自包含）。 */
    private String content;

    /** 来源指针（sessionId#runId 等，供原文回查）。 */
    private String sourceRef;

    /** 重要度 1-10（缺省 5）。 */
    private int importance;

    /** 状态（缺省 ACTIVE）。 */
    private MemoryStatus status;

    /** 乐观锁版本（写入时从 1 起）。 */
    private long version;

    /** 访问计数（"用进废退"，命中召回时 +1）。 */
    private long accessCount;

    /** 创建时刻。 */
    private Instant createdAt;

    /** 更新时刻。 */
    private Instant updatedAt;

    /** 最近召回时刻。 */
    private Instant lastAccessedAt;

    /** 溯源。 */
    private Provenance provenance;

    /**
     * 浅拷贝（更正/状态迁移用）。
     *
     * @return 副本
     */
    public MemoryItem copy() {
        return MemoryItem.builder()
                .memoryId(memoryId).scope(scope).kind(kind)
                .abstractText(abstractText).content(content).sourceRef(sourceRef)
                .importance(importance).status(status).version(version)
                .accessCount(accessCount).createdAt(createdAt).updatedAt(updatedAt)
                .lastAccessedAt(lastAccessedAt).provenance(provenance)
                .build();
    }
}
