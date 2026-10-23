// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.tool;

import com.cy.core.exception.CYException;

/**
 * 工具 harness 配置。
 *
 * @param timeoutMillis          单次工具执行超时（毫秒；超时强取消并返回失败回执）
 * @param argFuseThreshold       相同参数指纹熔断阈值（连续失败次数；≤0 关闭）
 * @param failureStreakThreshold 单工具连续失败升级阈值（≤0 关闭）
 * @author pcy
 * @since 2026/08/19
 */
public record ToolHarnessConfig(
        long timeoutMillis,
        int argFuseThreshold,
        int failureStreakThreshold) {

    /**
     * 构造并校验。
     *
     * @param timeoutMillis          超时毫秒
     * @param argFuseThreshold       指纹熔断阈值
     * @param failureStreakThreshold 失败升级阈值
     * @return 配置
     * @throws CYException 参数非法（50501）
     */
    public static ToolHarnessConfig of(long timeoutMillis,
                                       int argFuseThreshold, int failureStreakThreshold) {
        if (timeoutMillis <= 0) {
            throw new CYException(50501, "timeoutMillis=" + timeoutMillis);
        }
        return new ToolHarnessConfig(timeoutMillis,
                argFuseThreshold, failureStreakThreshold);
    }

    /**
     * 默认配置（超时 30s / 指纹熔断 2 / 失败升级 3）。
     *
     * @return 默认配置
     */
    public static ToolHarnessConfig defaults() {
        return new ToolHarnessConfig(30_000L, 2, 3);
    }
}
