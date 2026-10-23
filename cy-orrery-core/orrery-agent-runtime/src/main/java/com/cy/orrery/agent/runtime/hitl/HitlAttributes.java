// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;

/**
 * HITL 的上下文属性存储约定。
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class HitlAttributes {

    /** 挂起态契约键名（公开常量：检查点存储侧按此键识别「挂起运行」选用长 TTL）。 */
    public static final String SUSPENDED_STATE_KEY_NAME = "hitl.suspendedState";

    /** 挂起态契约键（引擎组装、宿主持久化时取出）。 */
    private static final AttributeKey<SuspendedState> SUSPENDED_STATE =
            new AttributeKey<>(SUSPENDED_STATE_KEY_NAME, SuspendedState.class);

    private HitlAttributes() {
    }

    /**
     * 存入挂起态契约。
     *
     * @param ctx   运行上下文
     * @param state 挂起态
     */
    public static void stashSuspendedState(AgentRunContext ctx, SuspendedState state) {
        ctx.putAttr(SUSPENDED_STATE, state);
    }

    /**
     * 读取挂起态契约。
     *
     * @param ctx 运行上下文
     * @return 挂起态；未挂起为 null
     */
    public static SuspendedState suspendedState(AgentRunContext ctx) {
        return ctx.getAttr(SUSPENDED_STATE);
    }

    /**
     * 清空挂起态契约（决定注入完成后调用）。
     *
     * @param ctx 运行上下文
     */
    public static void clearSuspendedState(AgentRunContext ctx) {
        ctx.putAttr(SUSPENDED_STATE, null);
    }
}
