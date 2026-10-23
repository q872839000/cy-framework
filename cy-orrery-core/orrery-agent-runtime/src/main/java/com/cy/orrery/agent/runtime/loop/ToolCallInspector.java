// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

/**
 * 工具结果检查 SPI：审批 / 澄清判定的注入点。
 * <p>
 * 引擎每次工具执行后依次调用两个检查方法；返回非空即触发对应挂起语义。
 * 领域的「哪些操作需要审批」（如变更类工具）由宿主实现表达。
 * </p>
 * <p>
 * ⚠️ <b>审批屏障契约（先执行后判定）</b>：检查发生在工具<b>真实执行之后</b>
 * （以执行结果为判定输入）——触发审批的调用在判定前已经产生副作用，
 * 屏障只拦截<b>同轮后续</b>调用（暂缓待批）。因此被审批工具的实现必须以
 * 「无副作用回执」响应本次调用（预演 / 试算 / 计划形态输出，真实落库动作
 * 延迟到批准后的确定性重放），否则"拒绝"无法阻止已发生的执行。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public interface ToolCallInspector {

    /**
     * 检查工具结果是否触发审批。
     *
     * @param result 工具执行结果
     * @return 审批请求；null 表示无需审批
     */
    ApprovalRequest inspectApproval(ToolCallResult result);

    /**
     * 检查工具结果是否触发审批（带运行上下文）。
     * <p>
     * 供需要在判定时读写宿主 carrier / 属性（如把审批负载暂存进 per-run carrier，
     * 供 {@code ToolRoundHooks.onRoundSettled} 接续）的实现使用。默认桥接单参版本。
     * </p>
     *
     * @param result 工具执行结果
     * @param ctx    运行上下文
     * @return 审批请求；null 表示无需审批
     */
    default ApprovalRequest inspectApproval(ToolCallResult result, com.cy.orrery.agent.runtime.context.AgentRunContext ctx) {
        return inspectApproval(result);
    }

    /**
     * 检查工具结果是否触发澄清。
     *
     * @param result 工具执行结果
     * @return 澄清请求；null 表示无需澄清
     */
    ClarificationRequest inspectClarification(ToolCallResult result);

    /**
     * 检查工具结果是否触发澄清（带运行上下文）。
     *
     * @param result 工具执行结果
     * @param ctx    运行上下文
     * @return 澄清请求；null 表示无需澄清
     */
    default ClarificationRequest inspectClarification(ToolCallResult result, com.cy.orrery.agent.runtime.context.AgentRunContext ctx) {
        return inspectClarification(result);
    }

    /**
     * 空实现（无审批 / 无澄清语义的宿主使用）。
     *
     * @author pcy
     * @since 2026/08/19
     */
    final class NoOp implements ToolCallInspector {

        /** 默认单例。 */
        public static final NoOp INSTANCE = new NoOp();

        private NoOp() {
        }

        @Override
        public ApprovalRequest inspectApproval(ToolCallResult result) {
            return null;
        }

        @Override
        public ClarificationRequest inspectClarification(ToolCallResult result) {
            return null;
        }
    }
}
