// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import com.cy.orrery.agent.runtime.context.AgentRunContext;

/**
 * 阶段处理器 SPI：每个阶段一个处理器，由 {@link StageHandlerRegistry} 按阶段标识分发。
 * <p>
 * 处理器只关注本阶段语义，通过 {@link StageOutcome} 表达流转决策；
 * 阶段间的共享状态经 {@link AgentRunContext} 属性表传递。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface StageHandler {

    /**
     * 本处理器负责的阶段标识。
     *
     * @return 阶段标识
     */
    String stage();

    /**
     * 执行阶段处理。
     *
     * @param ctx 运行上下文（阶段间共享状态经属性表传递）
     * @return 阶段处理结果（流转决策）
     */
    StageOutcome handle(AgentRunContext ctx);
}
