// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

/**
 * 循环终止原因。
 * <p>
 * 标识一次 Agent 循环退出的具体原因，宿主运行器据此决定后续行为：
 * 继续等待用户输入、进入审批流程、还是结束本次交互。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public enum AgentStop {

    /** 模型返回最终回答，无 tool_calls，正常结束。 */
    FINAL_ANSWER,

    /** 工具结果触发审批屏障，等待用户审批后重放暂缓调用。 */
    NEEDS_APPROVAL,

    /** 执行计划待用户整体确认（Plan-Driven Execution 交互模式）。 */
    NEEDS_PLAN_CONFIRMATION,

    /** 工具结果触发澄清挂起，等待用户补充信息。 */
    NEEDS_CLARIFICATION,

    /** 达到最大轮次上限，强制终止。 */
    MAX_STEPS,

    /** 业务层致命错误（工具不可自纠失败等）。 */
    BUSINESS_ERROR,

    /** LLM 调用异常（超时、格式错误等）。 */
    LLM_ERROR,

    /** 模型输出达到单次 max_tokens 上限被截断（可续写 / 可重试语义）。 */
    OUTPUT_TRUNCATED,

    /** 外部取消。 */
    CANCELLED
}
