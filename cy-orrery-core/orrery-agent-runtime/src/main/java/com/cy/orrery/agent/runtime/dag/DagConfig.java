// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.core.exception.CYException;

/**
 * DAG 执行配置。
 *
 * @param maxConcurrency    同层最大并行度（≥1）
 * @param nodeTimeoutMs     单节点超时毫秒（0 表示不限时；超时按可重试失败处理）
 * @param maxNodeExecutions 节点执行总次数熔断上限（跨挂起恢复累计，防重试风暴）
 * @author pcy
 */
public record DagConfig(int maxConcurrency, long nodeTimeoutMs, int maxNodeExecutions) {

    /**
     * 紧凑构造：与 {@link #of} 同一校验口径（绕过工厂直接 new 也不能产出非法配置——
     * maxConcurrency=0 会让信号量永久阻塞，必须在构造期拒绝）。
     */
    public DagConfig {
        if (maxConcurrency < 1 || nodeTimeoutMs < 0 || maxNodeExecutions < 1) {
            throw new CYException(50501,
                    "maxConcurrency=" + maxConcurrency + ", nodeTimeoutMs=" + nodeTimeoutMs
                            + ", maxNodeExecutions=" + maxNodeExecutions);
        }
    }

    /**
     * 构造并校验（与紧凑构造同语义，保留工厂风格入口）。
     *
     * @param maxConcurrency    同层最大并行度（≥1）
     * @param nodeTimeoutMs     单节点超时毫秒（≥0）
     * @param maxNodeExecutions 节点执行总次数熔断上限（≥1）
     * @return 配置
     * @throws CYException 参数非法（50501）
     */
    public static DagConfig of(int maxConcurrency, long nodeTimeoutMs, int maxNodeExecutions) {
        return new DagConfig(maxConcurrency, nodeTimeoutMs, maxNodeExecutions);
    }

    /**
     * 默认配置（并行 4 / 节点不限时 / 执行预算 100）。
     *
     * @return 默认配置
     */
    public static DagConfig defaults() {
        return new DagConfig(4, 0L, 100);
    }
}
