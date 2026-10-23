// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

/**
 * DAG 节点执行结果（密封类型）。
 * <p>
 * 处理器（{@link DagNodeHandler}）只允许返回 {@link Done} / {@link Failed} /
 * {@link Awaiting} 三种；{@link Dropped} 由引擎内部使用（上游失败级联放弃），
 * 处理器返回它会被视为非法并按失败兜底。
 * </p>
 *
 * @author pcy
 */
public sealed interface DagNodeOutcome
        permits DagNodeOutcome.Done, DagNodeOutcome.Failed,
                DagNodeOutcome.Awaiting, DagNodeOutcome.Dropped {

    /**
     * 节点完成。
     *
     * @param evidence 证据摘要（验收与汇总的消费材料，应精炼——它会进入下游节点入参）
     */
    record Done(String evidence) implements DagNodeOutcome {

        /**
         * 完成工厂（空证据规范化为空串）。
         *
         * @param evidence 证据摘要
         * @return 完成结果
         */
        public static Done of(String evidence) {
            return new Done(evidence == null ? "" : evidence);
        }
    }

    /**
     * 节点失败。
     *
     * @param code      失败错误码（字符串体系，对齐 RuntimeErrorCode）
     * @param message   失败消息
     * @param retryable 是否可重试（重试决策归宿主，引擎只透传）
     */
    record Failed(String code, String message, boolean retryable) implements DagNodeOutcome {

        /**
         * 失败工厂。
         *
         * @param code      错误码
         * @param message   失败消息
         * @param retryable 是否可重试
         * @return 失败结果
         */
        public static Failed of(String code, String message, boolean retryable) {
            return new Failed(code, message, retryable);
        }
    }

    /**
     * 节点挂起（等待外部信号：人工门 / 异步回调）。
     * <p>
     * 引擎收到挂起结果后：等待同层在途节点执行完，落盘已得结果，
     * 整个 DAG 以 {@link DagOutcome.Status#AWAITING} 返回；宿主持久化检查点，
     * 信号到达后重入 {@code run()}，挂起节点由处理器重新执行（是否已满足由宿主判定）。
     * </p>
     *
     * @param awaitingKey 挂起标识（恢复链路的 lookup 键，如 approvalId / stage 名）
     * @param message     给人看的等待说明
     */
    record Awaiting(String awaitingKey, String message) implements DagNodeOutcome {

        /**
         * 挂起工厂。
         *
         * @param awaitingKey 挂起标识
         * @param message     等待说明
         * @return 挂起结果
         */
        public static Awaiting of(String awaitingKey, String message) {
            return new Awaiting(awaitingKey, message);
        }
    }

    /**
     * 节点被放弃（引擎内部：上游失败级联 / 计划重排）。
     *
     * @param reason 放弃原因
     */
    record Dropped(String reason) implements DagNodeOutcome {
    }
}
