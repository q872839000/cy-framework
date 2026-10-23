// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 上下文窗口管理器（无状态模式门面：宿主自持消息列表，组件提供纯算法）。
 * <p>
 * 职责：token 估算 → 高水位判定 → 双档折叠（钉扎感知）。折叠为确定性操作；
 * 驱逐+滚动摘要在有状态模式（{@link ContextSession}）中提供。
 * 仅持有配置与适配器、无运行态，可跨 run 复用。
 * </p>
 *
 * @param <T> 宿主消息类型
 * @author pcy
 */
public class ContextWindowManager<T> {

    /** 折叠结果。 */
    public record CompactionResult(boolean compacted, boolean hardTier, int elided,
                                   int pinnedCount, int newBoundary) {
        public static CompactionResult none(int boundary) {
            return new CompactionResult(false, false, 0, 0, boundary);
        }
    }

    /** 窗口配置。 */
    private final ContextWindowConfig config;

    /** 宿主消息适配器。 */
    private final MessageAdapter<T> adapter;

    /**
     * 构造窗口管理器。
     *
     * @param config  窗口配置（null 用默认值）
     * @param adapter 宿主消息适配器
     */
    public ContextWindowManager(ContextWindowConfig config, MessageAdapter<T> adapter) {
        this.config = config == null ? ContextWindowConfig.defaults() : config;
        this.adapter = adapter;
    }

    /**
     * 估算消息列表总 token。
     */
    public int estimate(List<T> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (T m : messages) {
            total += adapter.estimateTokens(m);
        }
        return total;
    }

    /**
     * 是否到达高水位（常规阈值；用于告警，每 run 只报一次由调用方控制）。
     */
    public boolean isHighWater(int estimated) {
        return config.enabled() && estimated >= config.normalThresholdTokens();
    }

    /**
     * 按当前 token 量执行双档折叠。
     *
     * @param messages  消息列表（原地替换被折叠项）
     * @param boundary  已折叠边界（之前的段不再折叠）
     * @param pinned    钉扎下标集合（可空）
     * @return 折叠结果（含新边界）
     */
    public CompactionResult compactIfNeeded(List<T> messages, int boundary, Set<Integer> pinned) {
        return compactIfNeeded(messages, boundary, pinned == null ? null : (m, keepFrom) -> pinned);
    }

    /**
     * 按当前 token 量执行双档折叠（钉扎集合按保留区起点即时计算）。
     *
     * @param messages  消息列表（原地替换被折叠项）
     * @param boundary  已折叠边界（之前的段不再折叠）
     * @param pinner    钉扎下标提供者（可空 = 无钉扎；入参为消息列表与保留区起始下标）
     * @return 折叠结果（含新边界）
     */
    public CompactionResult compactIfNeeded(List<T> messages, int boundary,
                                            BiFunction<List<T>, Integer, Set<Integer>> pinner) {
        if (!config.enabled()) {
            return CompactionResult.none(boundary);
        }
        int estimated = estimate(messages);
        if (estimated < config.normalThresholdTokens()) {
            return CompactionResult.none(boundary);
        }
        long hardThreshold = config.hardThresholdTokens();
        boolean hardTier = hardThreshold > 0 && estimated >= hardThreshold;
        int keepRounds = hardTier ? 1 : config.recentKeepRounds();

        int safeBoundary = Math.max(0, boundary);
        int keepFrom = ContextCompactor.resolveKeepFromIndex(messages, adapter,
                Math.max(1, keepRounds), safeBoundary);
        Set<Integer> pinned = pinner == null ? Set.of() : pinner.apply(messages, keepFrom);
        int elided = ContextCompactor.compact(messages, adapter, safeBoundary, pinned, keepFrom);
        if (elided == 0) {
            return CompactionResult.none(safeBoundary);
        }
        return new CompactionResult(true, hardTier, elided, pinned.size(), keepFrom);
    }

    public ContextWindowConfig config() {
        return config;
    }
}
