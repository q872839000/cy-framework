// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api.support;

import com.cy.core.exception.CYException;

/**
 * 雪花 ID 生成器（41 位毫秒时间戳 + 10 位节点 + 12 位序列）。
 * <p>
 * 多实例部署必须为各实例配置不同 nodeId（cy.orrery.memory.node-id）；
 * 时钟回拨超过 5ms 容忍上限抛 50611（ID 撞车风险不可静默）。
 * </p>
 *
 * @author pcy
 */
public class SnowflakeIdGenerator {

    /** 时钟回拨容忍上限（毫秒）。 */
    private static final long MAX_BACKWARD_MS = 5L;

    /** 节点位数。 */
    private static final int NODE_BITS = 10;

    /** 序列位数。 */
    private static final int SEQUENCE_BITS = 12;

    /** 序列掩码。 */
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;

    /** 节点 ID。 */
    private final long nodeId;

    /** 上次发号毫秒。 */
    private long lastTimestamp = -1L;

    /** 毫秒内序列。 */
    private long sequence = 0L;

    /**
     * 构造发号器。
     *
     * @param nodeId 节点 ID（0-1023）
     */
    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > (1L << NODE_BITS) - 1) {
            throw new CYException(50613, String.valueOf(nodeId));
        }
        this.nodeId = nodeId;
    }

    /**
     * 发号（字符串形态，避免 JS 精度丢失）。
     *
     * @return 雪花 ID
     */
    public synchronized String nextId() {
        long now = System.currentTimeMillis();
        if (now < lastTimestamp) {
            long backward = lastTimestamp - now;
            if (backward > MAX_BACKWARD_MS) {
                throw new CYException(50611, String.valueOf(backward));
            }
            now = lastTimestamp;
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                while (now <= lastTimestamp) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = now;
        long id = (now << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
        return Long.toString(id);
    }
}
