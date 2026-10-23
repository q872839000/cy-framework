// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import java.util.List;
import java.util.Optional;

/**
 * 画像条目存储 SPI（MySQL 实现随组件提供）。
 *
 * @author pcy
 */
public interface ProfileStore {

    /**
     * 写入新条目。
     *
     * @param item 条目
     */
    void save(ProfileItem item);

    /**
     * 按 ID 读取。
     *
     * @param itemId 条目 ID
     * @return 条目
     */
    Optional<ProfileItem> get(String itemId);

    /**
     * 乐观锁更新。
     *
     * @param item            新值
     * @param expectedVersion 期望版本
     * @return 是否应用成功
     */
    boolean update(ProfileItem item, long expectedVersion);

    /**
     * 按属主 + 维度查询（dimension 为 null 查全部维度；含非 ACTIVE 由 includeInactive 控制）。
     *
     * @param ownerType      属主类型
     * @param tenantId       租户 ID（可空）
     * @param ownerId        属主 ID
     * @param dimension      维度 key（null = 全部）
     * @param includeInactive 是否含已作废条目
     * @return 条目列表（按 createdAt 升序）
     */
    List<ProfileItem> list(ProfileItem.OwnerType ownerType, String tenantId, String ownerId,
                           String dimension, boolean includeInactive);

    /**
     * 物理删除某属主的全部条目（被遗忘权）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @return 删除条数
     */
    int deleteByOwner(ProfileItem.OwnerType ownerType, String tenantId, String ownerId);
}
