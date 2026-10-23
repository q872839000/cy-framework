// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import java.util.Map;

/**
 * 审批屏障暂缓的工具调用。
 * <p>
 * 屏障命中时同轮后续调用按原参数暂存（配占位回执维持 tool_calls 配对协议），
 * 审批通过后由引擎确定性重放。
 * </p>
 *
 * @param callId    调用 ID
 * @param toolName  工具名
 * @param arguments 已解析参数（重放时按原参数执行）
 * @author pcy
 * @since 2026/08/19
 */
public record DeferredToolCall(String callId, String toolName, Map<String, Object> arguments) {
}
