// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import java.util.Map;

/**
 * DAG 执行结果。
 * <p>
 * {@code results} 是全量节点终态视图（含跨挂起恢复的历史结果），键为节点 ID；
 * 宿主做 fan-in 汇总、账本勾稽与证据收集时以此为唯一事实源。
 * </p>
 *
 * @param status        执行状态
 * @param results       节点 ID → 终态结果（仅含已到终态的节点；AWAITING 时挂起节点不在其中）
 * @param executedCount 本次运行实际调用处理器的次数（不含恢复的历史结果）
 * @param droppedCount  被级联放弃的节点数
 * @param awaitingKey   挂起标识（仅 AWAITING 时有值）
 * @param failedCode    失败错误码（FAILED / FUSED 时有值）
 * @param message       结果说明（失败消息 / 挂起说明 / 进度摘要）
 * @param retryable     失败是否可重试
 * @author pcy
 */
public record DagOutcome(Status status, Map<String, DagNodeOutcome> results,
                         int executedCount, int droppedCount, String awaitingKey,
                         String failedCode, String message, boolean retryable) {

    /**
     * DAG 执行状态。
     */
    public enum Status {

        /** 全部节点完成。 */
        DONE,

        /** 部分节点完成（存在失败 / 被放弃的节点，其余分支已尽力跑完）。 */
        PARTIAL,

        /** 无一节点完成（首层即失败或全部被放弃）。 */
        FAILED,

        /** 挂起（等待外部信号，结果已可持久化，恢复后重入）。 */
        AWAITING,

        /** 执行预算熔断（节点执行总次数超限）。 */
        FUSED
    }

    /**
     * 完成 / 部分完成结果工厂（引擎内部按结果集自动分档）。
     *
     * @param results       节点终态结果
     * @param executedCount 本次执行次数
     * @param droppedCount  放弃数
     * @param totalCount    节点总数
     * @return 结果
     */
    static DagOutcome settled(Map<String, DagNodeOutcome> results,
                              int executedCount, int droppedCount, int totalCount) {
        long doneCount = results.values().stream()
                .filter(o -> o instanceof DagNodeOutcome.Done).count();
        Status status = doneCount == totalCount ? Status.DONE
                : doneCount > 0 ? Status.PARTIAL : Status.FAILED;
        String message = "DAG 完成 " + doneCount + "/" + totalCount;
        String failedCode = null;
        boolean retryable = false;
        if (status != Status.DONE) {
            DagNodeOutcome firstFailure = results.values().stream()
                    .filter(o -> o instanceof DagNodeOutcome.Failed).findFirst().orElse(null);
            if (firstFailure instanceof DagNodeOutcome.Failed failed) {
                failedCode = failed.code();
                message = failed.message();
                retryable = failed.retryable();
            }
        }
        return new DagOutcome(status, Map.copyOf(results), executedCount, droppedCount,
                null, failedCode, message, retryable);
    }

    /**
     * 挂起结果工厂。
     *
     * @param results       已得结果
     * @param executedCount 本次执行次数
     * @param droppedCount  放弃数
     * @param awaitingKey   挂起标识
     * @param message       等待说明
     * @return 挂起结果
     */
    static DagOutcome awaiting(Map<String, DagNodeOutcome> results,
                               int executedCount, int droppedCount,
                               String awaitingKey, String message) {
        return new DagOutcome(Status.AWAITING, Map.copyOf(results), executedCount,
                droppedCount, awaitingKey, null, message, false);
    }

    /**
     * 熔断结果工厂（保留已得结果与执行计数——宿主 fan-in / 账本 / 证据的事实源不丢）。
     *
     * @param code          错误码
     * @param message       熔断说明
     * @param results       已得结果
     * @param executedCount 累计执行次数
     * @param droppedCount  放弃数
     * @return 熔断结果
     */
    static DagOutcome fused(String code, String message, Map<String, DagNodeOutcome> results,
                            int executedCount, int droppedCount) {
        return new DagOutcome(Status.FUSED, Map.copyOf(results), executedCount, droppedCount,
                null, code, message, true);
    }

    /**
     * 是否挂起（宿主据此决定持久化检查点而非终态结算）。
     *
     * @return true 表示挂起
     */
    public boolean suspending() {
        return status == Status.AWAITING;
    }
}
