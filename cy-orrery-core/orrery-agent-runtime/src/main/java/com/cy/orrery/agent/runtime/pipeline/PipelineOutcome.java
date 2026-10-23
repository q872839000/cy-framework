// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import java.util.List;

/**
 * 阶段链执行结果。
 *
 * @param status         终态类型
 * @param message        正文（Done / Awaiting）或失败消息（Failed）
 * @param awaitingStage  待确认阶段标识（Awaiting；恢复时按它 lookup 阶段——
 *                       缺失会让挂起-恢复链路必然断裂，因此作为一等字段传递）
 * @param code           失败错误码（Failed / 熔断）
 * @param retryable      失败可重试标志
 * @param question       裁定问题（AskUserDecision）
 * @param options        裁定选项（AskUserDecision）
 * @param targetEngine   目标引擎（SwitchEngine）
 * @param handoffPayload 切换载荷（SwitchEngine）
 * @param transitions    实际转移次数
 * @param rollbacks      实际回退次数
 * @author pcy
 * @since 2026/08/19
 */
public record PipelineOutcome(
        Status status,
        String message,
        String awaitingStage,
        String code,
        boolean retryable,
        String question,
        List<String> options,
        String targetEngine,
        String handoffPayload,
        int transitions,
        int rollbacks) {

    /** 链终态类型。 */
    public enum Status {

        /** 正常完成。 */
        DONE,

        /** 致命失败。 */
        FAILED,

        /** 等待用户确认（挂起）。 */
        AWAITING,

        /** 升级用户裁定（正常终态）。 */
        ASK_USER_DECISION,

        /** 切换引擎。 */
        SWITCH_ENGINE,

        /** 转移熔断。 */
        TRANSITIONS_EXCEEDED,

        /** 回退预算耗尽。 */
        ROLLBACKS_EXCEEDED
    }

    /**
     * 是否挂起类结果（非真终态：运行未结束，宿主接管后续编排）。
     * <p>
     * Awaiting 等待用户确认后重入链继续；SwitchEngine 由目标引擎接管（宿主持有
     * 上下文继续）。两者都不得清理链预算计数（转移 / 回退），否则挂起重入后
     * 预算被重新武装，熔断保护失效。
     * </p>
     *
     * @return true 表示挂起类（AWAITING / SWITCH_ENGINE）
     */
    public boolean suspending() {
        return status == Status.AWAITING || status == Status.SWITCH_ENGINE;
    }

    /**
     * 正常完成工厂。
     *
     * @param message     完成正文
     * @param transitions 转移次数
     * @param rollbacks   回退次数
     * @return 结果
     */
    public static PipelineOutcome done(String message, int transitions, int rollbacks) {
        return new PipelineOutcome(Status.DONE, message, null, null, false, null, null,
                null, null, transitions, rollbacks);
    }

    /**
     * 致命失败工厂。
     *
     * @param code        错误码
     * @param message     失败消息
     * @param retryable   可重试标志
     * @param transitions 转移次数
     * @param rollbacks   回退次数
     * @return 结果
     */
    public static PipelineOutcome failed(String code, String message, boolean retryable,
                                         int transitions, int rollbacks) {
        return new PipelineOutcome(Status.FAILED, message, null, code, retryable, null, null,
                null, null, transitions, rollbacks);
    }

    /**
     * 熔断失败工厂（转移 / 回退熔断共用）。
     *
     * @param code        错误码
     * @param message     熔断消息
     * @param status      熔断类型
     * @param transitions 转移次数
     * @param rollbacks   回退次数
     * @return 结果
     */
    public static PipelineOutcome fused(String code, String message, Status status,
                                         int transitions, int rollbacks) {
        return new PipelineOutcome(status, message, null, code, true, null, null,
                null, null, transitions, rollbacks);
    }

    /**
     * 等待确认工厂。
     *
     * @param message      等待说明（给人看）
     * @param stage        待确认阶段标识（给恢复链路用——不得与 message 混用）
     * @param transitions  转移次数
     * @param rollbacks    回退次数
     * @return 结果
     */
    public static PipelineOutcome awaiting(String message, String stage,
                                            int transitions, int rollbacks) {
        return new PipelineOutcome(Status.AWAITING, message, stage, null, false, null, null,
                null, null, transitions, rollbacks);
    }

    /**
     * 用户裁定工厂。
     *
     * @param question    裁定问题
     * @param options     选项
     * @param transitions 转移次数
     * @param rollbacks   回退次数
     * @return 结果
     */
    public static PipelineOutcome askUser(String question, List<String> options,
                                           int transitions, int rollbacks) {
        return new PipelineOutcome(Status.ASK_USER_DECISION, null, null, null, false,
                question, options, null, null, transitions, rollbacks);
    }

    /**
     * 引擎切换工厂。
     *
     * @param targetEngine   目标引擎
     * @param handoffPayload 切换载荷
     * @param transitions    转移次数
     * @param rollbacks      回退次数
     * @return 结果
     */
    public static PipelineOutcome switchEngine(String targetEngine, String handoffPayload,
                                                int transitions, int rollbacks) {
        return new PipelineOutcome(Status.SWITCH_ENGINE, null, null, null, false, null, null,
                targetEngine, handoffPayload, transitions, rollbacks);
    }
}
