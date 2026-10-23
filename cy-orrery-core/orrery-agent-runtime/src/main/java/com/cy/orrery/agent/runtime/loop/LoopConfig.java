// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import com.cy.core.exception.CYException;

/**
 * 循环引擎配置。
 *
 * @param maxRounds                  最大轮次
 * @param maxConsecutiveFailedRounds 退化熔断阈值（连续「工具失败且零业务进展」轮数）
 * @param maxConsecutiveIdleRounds   收敛熔断阈值（连续「零事实进展」无工具调用轮数）
 * @author pcy
 * @since 2026/08/19
 */
public record LoopConfig(
        int maxRounds,
        int maxConsecutiveFailedRounds,
        int maxConsecutiveIdleRounds) {

    /**
     * 构造并校验配置。
     *
     * @param maxRounds                  最大轮次（≥1）
     * @param maxConsecutiveFailedRounds 退化熔断阈值（≥2）
     * @param maxConsecutiveIdleRounds   收敛熔断阈值（≥2）
     * @return 配置
     * @throws CYException 参数非法（50501）
     */
    public static LoopConfig of(int maxRounds, int maxConsecutiveFailedRounds, int maxConsecutiveIdleRounds) {
        if (maxRounds < 1 || maxConsecutiveFailedRounds < 2 || maxConsecutiveIdleRounds < 2) {
            throw new CYException(50501,
                    "maxRounds=" + maxRounds + ", maxConsecutiveFailedRounds=" + maxConsecutiveFailedRounds
                            + ", maxConsecutiveIdleRounds=" + maxConsecutiveIdleRounds);
        }
        return new LoopConfig(maxRounds, maxConsecutiveFailedRounds, maxConsecutiveIdleRounds);
    }

    /**
     * 默认配置（25 / 3 / 3）。
     *
     * @return 默认配置
     */
    public static LoopConfig defaults() {
        return new LoopConfig(25, 3, 3);
    }
}
