// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import com.cy.core.exception.CYException;

/**
 * 阶段链配置。
 *
 * @param maxTransitions 最大阶段转移次数（含回退，熔断上限）
 * @param maxRollbacks   最大回退次数（回退预算）
 * @author pcy
 * @since 2026/08/19
 */
public record PipelineConfig(int maxTransitions, int maxRollbacks) {

    /**
     * 构造并校验。
     *
     * @param maxTransitions 最大转移数（≥1）
     * @param maxRollbacks   最大回退数（≥1）
     * @return 配置
     * @throws CYException 参数非法（50501）
     */
    public static PipelineConfig of(int maxTransitions, int maxRollbacks) {
        if (maxTransitions < 1 || maxRollbacks < 1) {
            throw new CYException(50501,
                    "maxTransitions=" + maxTransitions + ", maxRollbacks=" + maxRollbacks);
        }
        return new PipelineConfig(maxTransitions, maxRollbacks);
    }

    /**
     * 默认配置（50 / 5）。
     *
     * @return 默认配置
     */
    public static PipelineConfig defaults() {
        return new PipelineConfig(50, 5);
    }
}
