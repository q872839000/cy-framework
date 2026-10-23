// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

/**
 * 单次循环的可变累加器。
 * <p>
 * 在主循环与各协作组件（工具轮引擎、领域钩子）之间传递的单运行可变状态；
 * 循环结束由驱动器折叠为不可变的 {@link LoopOutcome}。
 * 字段公开可写是设计选择：各协作方直接读写，避免长参数列表。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class LoopAccumulator {

    /** 已执行轮次。 */
    public int rounds;

    /** 累计工具调用次数。 */
    public int toolCallCount;

    /** 累计 prompt token。 */
    public int promptTokens;

    /** 累计 completion token。 */
    public int completionTokens;

    /** 终止原因（默认 MAX_STEPS，各终止路径覆写）。 */
    public AgentStop stop = AgentStop.MAX_STEPS;

    /** 模型终轮正文。 */
    public String finalText;

    /** 失败消息（正常路径为 null）。 */
    public String errorMsg;

    /** 失败错误码（业务字符串码，正常路径为 null）。 */
    public String failedCode;

    /** 失败是否可重试。 */
    public boolean failedRetryable;
}
