// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.LoopAttributes;
import com.cy.orrery.agent.runtime.loop.ToolRoundHooks;

/**
 * 审批决定注入器：把决定转成对话历史里的合成回执（L2 注入协议）。
 * <p>
 * 注入语义：批准 → 合成回执覆盖触发审批调用的占位回执，<b>保留</b>暂缓调用
 * （引擎下轮重放执行）；拒绝 / 超时 → 拒绝回执 + <b>清空</b>暂缓调用
 * （兄弟调用不再执行）。回执文案经 {@link ToolRoundHooks} 钩子供给。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class ApprovalDecisionApplier {

    private ApprovalDecisionApplier() {
    }

    /**
     * 注入单个审批决定。
     *
     * @param ctx       运行上下文
     * @param approval  审批请求（定位触发调用的 callId）
     * @param decision  审批决定
     * @param hooks     领域钩子（回执文案供给）
     */
    public static void apply(AgentRunContext ctx, ApprovalRequest approval,
                             ApprovalDecision decision, ToolRoundHooks hooks) {
        if (decision.approved()) {
            ctx.replaceOrAddToolMessage(approval.callId(),
                    hooks.approvalApprovedReceipt(approval));
            return;
        }
        ctx.replaceOrAddToolMessage(approval.callId(),
                hooks.approvalRejectedReceipt(approval, decision.timedOut()));
        LoopAttributes.clearDeferredCalls(ctx);
    }
}
