// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.episodic;

import java.util.Optional;

/**
 * 情景卡片存储 SPI（Redis 实现随组件提供）。
 *
 * @author pcy
 */
public interface EpisodeStore {

    /**
     * 写入/覆写（同 sessionId 滚动更新，TTL 滑动续期）。
     *
     * @param record 情景卡片
     */
    void save(EpisodeRecord record);

    /**
     * 按 ID 读取（过期返回 empty）。
     *
     * @param episodeId 情景 ID
     * @return 卡片
     */
    Optional<EpisodeRecord> get(String episodeId);

    /**
     * 删除（隐私擦除联动）。
     *
     * @param episodeId 情景 ID
     */
    void delete(String episodeId);

    /**
     * 是否可用。
     *
     * @return 可用性
     */
    boolean available();
}
