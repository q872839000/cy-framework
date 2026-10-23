// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.llm;

import com.cy.orrery.agent.runtime.loop.AgentStop;

/**
 * LLM 重试策略：仅传输级瞬时失败做有界指数退避重试。
 * <p>
 * 重试判定：失败、可重试标志为真（{@code failedRetryable()}）且 stopHint 为 null 或 LLM_ERROR。
 * 确定性结局（OUTPUT_TRUNCATED / 拒绝类 / 内容过滤 / 4xx）与「已发射正文块的流式失败」
 * 由宿主适配器在 {@code LlmRoundResult.failedRetryable=false} 上表达，一律不重试——
 * 重试会把已下发的用户可见块重复推送。退避 = base × 2^(n-1)。
 * </p>
 *
 * @param maxRetries       最大重试次数（不含首次；0 = 关闭重试）
 * @param backoffBaseMillis 退避基准毫秒
 * @author pcy
 * @since 2026/08/19
 */
public record LlmRetryPolicy(int maxRetries, long backoffBaseMillis) {

    /**
     * 默认策略（重试 2 次，基准 800ms）。
     *
     * @return 默认策略
     */
    public static LlmRetryPolicy defaults() {
        return new LlmRetryPolicy(2, 800L);
    }

    /**
     * 是否应对该失败结果重试。
     *
     * @param result LLM 轮结果
     * @return true 表示可重试
     */
    public boolean shouldRetry(LlmInvoker.LlmRoundResult result) {
        if (!result.failed()) {
            return false;
        }
        // 可重试标志是适配器的唯一权威表达：已发射正文块的流式失败 / 4xx / 内容过滤
        // 均置 false（重试只会重复推送已下发块或得到同样的确定性结果）。
        if (!result.failedRetryable()) {
            return false;
        }
        return result.stopHint() == null || result.stopHint() == AgentStop.LLM_ERROR;
    }

    /**
     * 第 n 次重试前的退避毫秒（指数退避）。
     *
     * @param attempt 重试序号（从 1 起）
     * @return 退避毫秒
     */
    public long backoffMillis(int attempt) {
        // attempt 钳制到 63：超过 63 的移位溢出为负值（Thread.sleep 抛 IllegalArgumentException）
        int safe = Math.min(Math.max(1, attempt), 63);
        return backoffBaseMillis * (1L << (safe - 1));
    }
}
