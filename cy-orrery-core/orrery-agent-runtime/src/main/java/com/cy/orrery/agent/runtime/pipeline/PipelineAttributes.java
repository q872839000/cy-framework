// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.pipeline;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;

/**
 * 阶段链引擎的上下文属性存储约定。
 * <p>
 * 转移 / 回退计数经 {@link AgentRunContext} 属性表携带：挂起（Awaiting）后宿主
 * 重新调 {@code run()} 时从属性恢复累计值，预算不被重新武装——与宿主把预算
 * 计数放进跨挂起运行状态的语义对齐。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class PipelineAttributes {

    /** 转移计数键名（含回退；检查点序列化器按此键做强类型注册）。 */
    public static final String TRANSITIONS_KEY_NAME = "pipeline.transitions";

    /** 回退计数键名。 */
    public static final String ROLLBACKS_KEY_NAME = "pipeline.rollbacks";

    /** 转移计数键（含回退）。 */
    static final AttributeKey<Integer> TRANSITIONS =
            new AttributeKey<>(TRANSITIONS_KEY_NAME, Integer.class);

    /** 回退计数键。 */
    static final AttributeKey<Integer> ROLLBACKS =
            new AttributeKey<>(ROLLBACKS_KEY_NAME, Integer.class);

    private PipelineAttributes() {
    }

    /**
     * 恢复累计转移数（缺省 0）。
     *
     * @param ctx 运行上下文
     * @return 累计转移数
     */
    public static int restoreTransitions(AgentRunContext ctx) {
        Integer value = ctx.getAttr(TRANSITIONS);
        return value == null ? 0 : value;
    }

    /**
     * 暂存累计转移数。
     *
     * @param ctx 运行上下文
     * @param transitions 累计转移数
     */
    public static void stashTransitions(AgentRunContext ctx, int transitions) {
        ctx.putAttr(TRANSITIONS, transitions);
    }

    /**
     * 恢复累计回退数（缺省 0）。
     *
     * @param ctx 运行上下文
     * @return 累计回退数
     */
    public static int restoreRollbacks(AgentRunContext ctx) {
        Integer value = ctx.getAttr(ROLLBACKS);
        return value == null ? 0 : value;
    }

    /**
     * 暂存累计回退数。
     *
     * @param ctx 运行上下文
     * @param rollbacks 累计回退数
     */
    public static void stashRollbacks(AgentRunContext ctx, int rollbacks) {
        ctx.putAttr(ROLLBACKS, rollbacks);
    }

    /**
     * 清空计数（链正常终态后调用，防同 ctx 复用残留）。
     *
     * @param ctx 运行上下文
     */
    public static void clear(AgentRunContext ctx) {
        ctx.putAttr(TRANSITIONS, null);
        ctx.putAttr(ROLLBACKS, null);
    }
}
