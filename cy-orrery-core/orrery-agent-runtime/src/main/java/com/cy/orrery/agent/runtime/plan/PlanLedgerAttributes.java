// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.plan;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;

/**
 * 任务账本的上下文属性存储约定。
 * <p>
 * 账本经 {@link AgentRunContext} 属性表携带：宿主序列化持久化上下文时随属性
 * 一并保存，跨挂起-恢复（审批 / 澄清 / 续跑）不丢。未装配账本（属性为 null）
 * 时引擎完全跳过勾稽与提前收敛，零开销。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class PlanLedgerAttributes {

    /** 账本键名（检查点序列化器按此键做强类型注册）。 */
    public static final String LEDGER_KEY_NAME = "plan.ledger";

    private static final AttributeKey<PlanLedger> LEDGER =
            new AttributeKey<>(LEDGER_KEY_NAME, PlanLedger.class);

    private PlanLedgerAttributes() {
    }

    /**
     * 存入账本。
     *
     * @param ctx    运行上下文
     * @param ledger 账本
     */
    public static void stash(AgentRunContext ctx, PlanLedger ledger) {
        ctx.putAttr(LEDGER, ledger);
    }

    /**
     * 读取账本。
     *
     * @param ctx 运行上下文
     * @return 账本；未装配为 null
     */
    public static PlanLedger ledger(AgentRunContext ctx) {
        return ctx.getAttr(LEDGER);
    }

    /**
     * 清空账本（终局后调用，防同上下文复用残留）。
     *
     * @param ctx 运行上下文
     */
    public static void clear(AgentRunContext ctx) {
        ctx.putAttr(LEDGER, null);
    }
}
