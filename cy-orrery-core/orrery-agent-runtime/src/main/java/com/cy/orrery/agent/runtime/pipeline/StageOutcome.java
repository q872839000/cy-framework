// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import java.util.List;

/**
 * 阶段处理结果：sealed interface 限定八种流转语义。
 * <p>
 * 处理器只关注本阶段语义，通过返回值表达推进 / 完成 / 待确认 / 回退 / 引擎切换，
 * 不直接控制编排循环。八态与 scenario-agent 的 Workflow StageOutcome 对齐，
 * 领域类型（如 ConsistencyFeedback）抽象为字符串载荷。
 * </p>
 * <ul>
 *     <li>{@link Continue} —— 推进到下一阶段；</li>
 *     <li>{@link Done} —— 链<b>正常完成</b>并返回文本；</li>
 *     <li>{@link Failed} —— 链<b>致命失败中止</b>（错误码 + 消息 + 可重试标志）；</li>
 *     <li>{@link Awaiting} —— 等待用户确认后继续；</li>
 *     <li>{@link AskUserDecision} —— 阻断问题升级用户裁定（正常终态，菜单进对话历史）；</li>
 *     <li>{@link Rollback} —— 回退到指定阶段重试（受回退预算约束）；</li>
 *     <li>{@link ContinueLoop} —— 不改阶段直接继续（短路跳转）；</li>
 *     <li>{@link SwitchEngine} —— 切换执行引擎（如切到 ReAct 增量修正，载荷透传）。</li>
 * </ul>
 * <p>
 * <b>Done 与 Failed 的边界</b>：Done 仅表示正常完成；任何「用户需改变输入才能恢复」的
 * 失败必须用 Failed，让宿主发失败终态事件，避免失败误走正常终态造成语义错乱。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public sealed interface StageOutcome
        permits StageOutcome.Continue, StageOutcome.Done, StageOutcome.Failed,
        StageOutcome.Awaiting, StageOutcome.AskUserDecision, StageOutcome.Rollback,
        StageOutcome.ContinueLoop, StageOutcome.SwitchEngine {

    /**
     * 推进到 {@code nextStage} 阶段。
     *
     * @param nextStage 下一阶段标识
     */
    record Continue(String nextStage) implements StageOutcome {
    }

    /**
     * 链正常完成，返回 {@code message} 给调用方。
     *
     * @param message 完成正文
     */
    record Done(String message) implements StageOutcome {
    }

    /**
     * 链致命失败中止。
     *
     * @param code      业务错误码（字符串）
     * @param message   面向用户的失败消息
     * @param retryable 是否可重试
     */
    record Failed(String code, String message, boolean retryable) implements StageOutcome {
    }

    /**
     * 等待用户确认（阶段级挂起，恢复语义由宿主承担）。
     *
     * @param message   等待说明
     * @param awaitingStage 待确认阶段标识
     */
    record Awaiting(String message, String awaitingStage) implements StageOutcome {
    }

    /**
     * 升级用户裁定：阻断问题超出自动修复能力，本轮以「提交当前进展 + 提问裁定」终止。
     *
     * @param question 裁定问题
     * @param options  编号处置选项
     */
    record AskUserDecision(String question, List<String> options) implements StageOutcome {
    }

    /**
     * 回退到 {@code targetStage} 阶段重试。
     *
     * @param targetStage 目标阶段
     * @param reason      回退原因
     */
    record Rollback(String targetStage, String reason) implements StageOutcome {
    }

    /** 不改阶段直接继续循环（意图短路等跳转）。 */
    record ContinueLoop() implements StageOutcome {
    }

    /**
     * 切换执行引擎：保留已产出进展，携带切换载荷交给另一引擎（如 ReAct）增量修正。
     *
     * @param targetEngine   目标引擎标识（宿主约定，如 "react"）
     * @param handoffPayload 切换载荷（如校验反馈 JSON，由目标引擎注入提示）
     * @param reason         切换原因
     */
    record SwitchEngine(String targetEngine, String handoffPayload, String reason) implements StageOutcome {
    }
}
