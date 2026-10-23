// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 画像条目（一条画像 = 一个维度的一个值，带证据与置信标记）。
 *
 * @author pcy
 */
@Data
@Builder
@AllArgsConstructor
public class ProfileItem {

    /**
     * 属主类型。
     */
    public enum OwnerType {
        /** 用户。 */
        USER,
        /** Agent。 */
        AGENT
    }

    /**
     * 条目状态（作废不删除）。
     */
    public enum Status {
        /** 生效中。 */
        ACTIVE,
        /** 被新条目取代。 */
        SUPERSEDED,
        /** 显式作废。 */
        INVALIDATED
    }

    /** 条目 ID（雪花，缺省自动生成）。 */
    private String itemId;

    /** 属主类型。 */
    private OwnerType ownerType;

    /** 属主 ID（userId / agentId）。 */
    private String ownerId;

    /** 租户 ID（可空）。 */
    private String tenantId;

    /** 维度 key。 */
    private String dimension;

    /** 值（条目正文）。 */
    private String value;

    /** 是否行为推断（false = 用户/Agent 显式陈述）。 */
    private boolean inferred;

    /** 证据（对话原文依据）。 */
    private String evidence;

    /** 状态。 */
    private Status status;

    /** 乐观锁版本。 */
    private long version;

    /** 创建时刻。 */
    private Instant createdAt;

    /** 更新时刻。 */
    private Instant updatedAt;

    /**
     * 浅拷贝（仲裁状态迁移用）。
     *
     * @return 副本
     */
    public ProfileItem copy() {
        return ProfileItem.builder()
                .itemId(itemId).ownerType(ownerType).ownerId(ownerId).tenantId(tenantId)
                .dimension(dimension).value(value).inferred(inferred).evidence(evidence)
                .status(status).version(version).createdAt(createdAt).updatedAt(updatedAt)
                .build();
    }
}
