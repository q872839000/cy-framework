// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.context;

import com.cy.core.exception.CYException;

/**
 * 上下文窗口配置（双档阈值 + 保留轮次）。
 *
 * @param windowLimit       窗口 token 上限（≤0 关闭守望）
 * @param compressThreshold 常规档阈值（占窗口比例，0-1）
 * @param hardThreshold     硬档阈值（占窗口比例，0-1；0 = 不启用硬档）
 * @param recentKeepRounds  保留区轮次（最近 N 轮永不折叠、永不驱逐）
 * @author pcy
 */
public record ContextWindowConfig(int windowLimit, double compressThreshold,
                                  double hardThreshold, int recentKeepRounds) {

    /**
     * 构造并校验。
     *
     * @throws CYException 配置非法（50614）
     */
    public ContextWindowConfig {
        if (windowLimit > 0) {
            if (compressThreshold <= 0 || compressThreshold >= 1) {
                throw new CYException(50614, "compressThreshold 须在 (0,1): " + compressThreshold);
            }
            if (hardThreshold < 0 || hardThreshold >= 1) {
                throw new CYException(50614, "hardThreshold 须在 [0,1): " + hardThreshold);
            }
            if (hardThreshold > 0 && hardThreshold <= compressThreshold) {
                throw new CYException(50614,
                        "hardThreshold 必须大于 compressThreshold: " + hardThreshold);
            }
            if (recentKeepRounds < 1) {
                throw new CYException(50614, "recentKeepRounds 须 ≥1: " + recentKeepRounds);
            }
        }
    }

    /**
     * 默认配置（32k 窗口，0.8/0.92 双档，保留 3 轮）。
     *
     * @return 默认配置
     */
    public static ContextWindowConfig defaults() {
        return new ContextWindowConfig(32000, 0.8, 0.92, 3);
    }

    /**
     * 守望是否开启。
     *
     * @return 开启
     */
    public boolean enabled() {
        return windowLimit > 0;
    }

    /**
     * 常规档阈值（token 数）。
     *
     * @return 阈值
     */
    public long normalThresholdTokens() {
        return Math.round(windowLimit * compressThreshold);
    }

    /**
     * 硬档阈值（token 数；0 = 不启用）。
     *
     * @return 阈值
     */
    public long hardThresholdTokens() {
        return hardThreshold > 0 ? Math.round(windowLimit * hardThreshold) : 0;
    }
}
