// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;

import com.cy.core.exception.CYException;

/**
 * 上下文守望配置。
 *
 * @param contextWindowLimit     上下文窗口 token 上限（≤0 关闭守望）
 * @param compressThreshold      常规压缩阈值（占窗口比例，如 0.8）
 * @param hardCompressThreshold  硬保护档阈值（占窗口比例，如 0.92；≤0 关闭硬档）
 * @param recentKeepCount        常规档保留轮数（硬档固定保留 1 轮）
 * @author pcy
 * @since 2026/08/19
 */
public record ContextGuardConfig(
        int contextWindowLimit,
        double compressThreshold,
        double hardCompressThreshold,
        int recentKeepCount) {

    /**
     * 构造并校验。
     *
     * @param contextWindowLimit    窗口上限
     * @param compressThreshold     常规阈值（0, 1]
     * @param hardCompressThreshold 硬档阈值（0, 1]；0 关闭
     * @param recentKeepCount       保留轮数（≥1）
     * @return 配置
     * @throws CYException 参数非法（50501）
     */
    public static ContextGuardConfig of(int contextWindowLimit, double compressThreshold,
                                        double hardCompressThreshold, int recentKeepCount) {
        if (compressThreshold <= 0 || compressThreshold > 1
                || hardCompressThreshold < 0 || hardCompressThreshold > 1
                || recentKeepCount < 1) {
            throw new CYException(50501,
                    "contextWindowLimit=" + contextWindowLimit + ", compressThreshold=" + compressThreshold
                            + ", hardCompressThreshold=" + hardCompressThreshold
                            + ", recentKeepCount=" + recentKeepCount);
        }
        return new ContextGuardConfig(contextWindowLimit, compressThreshold, hardCompressThreshold, recentKeepCount);
    }

    /**
     * 默认配置（32000 / 0.8 / 0.92 / 3）。
     *
     * @return 默认配置
     */
    public static ContextGuardConfig defaults() {
        return new ContextGuardConfig(32000, 0.8, 0.92, 3);
    }
}
