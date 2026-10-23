// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.harness;

import com.cy.orrery.agent.runtime.context.AgentRunContext;

/**
 * 上下文守望 SPI：每轮 LLM 调用前的上下文卫生处理。
 * <p>
 * 职责：token 预算估算、高水位告警、逼近上限时的折叠压缩（含领域钉扎）。
 * 实现应保证幂等（连续调用无副作用累积）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface ContextGuard {

    /**
     * LLM 调用前的上下文准备。
     *
     * @param ctx 运行上下文
     * @return 处理后的估算 token 数
     */
    int prepareForLlmCall(AgentRunContext ctx);
}
