// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * 记忆审计修订（一切变更留痕；ERASE/PURGE 事件快照为脱敏占位）。
 *
 * @author pcy
 */
@Value
@Builder
public class MemoryRevision {

    /**
     * 审计操作类型。
     */
    public enum Op {
        /** 创建。 */
        CREATE,
        /** 更正。 */
        UPDATE,
        /** 被取代。 */
        SUPERSEDE,
        /** 作废。 */
        INVALIDATE,
        /** 隐私擦除（快照脱敏）。 */
        ERASE,
        /** 治理清理。 */
        PURGE
    }

    /** 修订 ID。 */
    String revisionId;

    /** 记忆 ID。 */
    String memoryId;

    /** 操作。 */
    Op op;

    /** 变更前快照 JSON（ERASE/PURGE 为 {"redacted":true}）。 */
    String beforeJson;

    /** 变更后快照 JSON（删除类操作为 null）。 */
    String afterJson;

    /** 操作者。 */
    String actorId;

    /** 依据。 */
    String reason;

    /** 发生时刻。 */
    Instant at;
}
