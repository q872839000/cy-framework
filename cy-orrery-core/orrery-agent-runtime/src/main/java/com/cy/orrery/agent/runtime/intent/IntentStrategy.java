// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.intent;

import com.cy.orrery.agent.runtime.context.AgentRunContext;

/**
 * 意图执行策略：一个意图键对应的执行单元。
 * <p>
 * 策略内部自由选择执行引擎（{@code ReactLoopEngine} / {@code PipelineEngine} /
 * 自有逻辑），统一返回宿主定义的结果类型 R——runtime 不规定两种引擎结果
 * 的统一形态（强行统一会漏水），由宿主的 R 承载。
 * </p>
 *
 * @param <R> 宿主定义的策略结果类型
 * @author pcy
 * @since 2026/08/19
 */
public interface IntentStrategy<R> {

    /**
     * 策略名（注册键，与意图识别产物的意图键对应）。
     *
     * @return 策略名
     */
    String name();

    /**
     * 执行策略。
     *
     * @param ctx 运行上下文
     * @return 策略结果（宿主类型）
     */
    R execute(AgentRunContext ctx);
}
