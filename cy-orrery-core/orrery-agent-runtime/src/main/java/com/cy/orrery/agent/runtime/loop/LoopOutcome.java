// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import java.util.List;

/**
 * 循环结果。
 * <p>
 * 主循环的不可变产出：统计、终止原因、终局正文与业务动作日志。
 * 领域附加结果（如未完成实例数）由宿主适配层基于运行上下文补充，不在此膨胀。
 * </p>
 *
 * @param rounds           实际轮次
 * @param toolCallCount    累计工具调用次数
 * @param promptTokens     累计 prompt token
 * @param completionTokens 累计 completion token
 * @param stop             终止原因
 * @param finalText        终轮正文
 * @param errorMsg         失败消息
 * @param actionLog        业务动作日志（成功执行的业务动作叙述）
 * @param failedCode       失败错误码
 * @param failedRetryable  失败可重试标志
 * @author pcy
 * @since 2026/08/19
 */
public record LoopOutcome(
        int rounds,
        int toolCallCount,
        int promptTokens,
        int completionTokens,
        AgentStop stop,
        String finalText,
        String errorMsg,
        List<String> actionLog,
        String failedCode,
        boolean failedRetryable) {
}
