// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;

import java.util.Map;

/**
 * Agent 运行时事件。
 * <p>
 * 由 {@link RuntimeEventBus} 统一分配单调递增的 {@code seq}，保证订阅方按序消费与断点续传定位。
 * {@code data} 为开放结构（Map），字段由发射方约定；终态语义由 {@link RuntimeEventType#terminal()} 决定。
 * </p>
 *
 * @param seq             单调递增序号（事件总线内唯一）
 * @param type            事件类型
 * @param runId           运行 ID
 * @param data            事件数据（开放结构，可为空 Map）
 * @param terminal        是否终态事件
 * @param timestampMillis 发射时间戳（毫秒）
 * @author pcy
 * @since 2026/08/19
 */
public record RuntimeEvent(
        long seq,
        RuntimeEventType type,
        String runId,
        Map<String, Object> data,
        boolean terminal,
        long timestampMillis) {
}
