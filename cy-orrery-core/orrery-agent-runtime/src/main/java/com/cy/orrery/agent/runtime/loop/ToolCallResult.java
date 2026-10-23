// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

/**
 * 单次工具调用的执行结果。
 * <p>
 * 引擎只消费该归一化结构：回执 JSON 写入 tool 消息、成功标志驱动业务动作收集、
 * 失败码驱动部分失败结算。领域细节全部封在 {@code resultJson} 内。
 * </p>
 *
 * @param callId         调用 ID（与 LLM tool_call id 配对）
 * @param toolName       工具名
 * @param success        是否成功
 * @param resultJson     回执 JSON（写入 tool 消息的 content）
 * @param userMessage    用户可读叙述（可空；成功路径用于业务动作日志收集）
 * @param errorCode      失败错误码（成功为 null）
 * @param errorMessage   失败消息（成功为 null）
 * @param partialFailure 是否部分失败（批量操作中部分成功）
 * @author pcy
 * @since 2026/08/19
 */
public record ToolCallResult(
        String callId,
        String toolName,
        boolean success,
        String resultJson,
        String userMessage,
        String errorCode,
        String errorMessage,
        boolean partialFailure) {

    /**
     * 成功结果工厂。
     *
     * @param callId      调用 ID
     * @param toolName    工具名
     * @param resultJson  回执 JSON
     * @param userMessage 用户叙述（可空）
     * @return 成功结果
     */
    public static ToolCallResult ok(String callId, String toolName, String resultJson, String userMessage) {
        return new ToolCallResult(callId, toolName, true, resultJson, userMessage, null, null, false);
    }

    /**
     * 失败结果工厂。
     *
     * @param callId       调用 ID
     * @param toolName     工具名
     * @param errorCode    失败错误码
     * @param errorMessage 失败消息
     * @param resultJson   回执 JSON（可空；空时引擎以 errorMessage 代替写入 tool 消息）
     * @return 失败结果
     */
    public static ToolCallResult fail(String callId, String toolName,
                                      String errorCode, String errorMessage, String resultJson) {
        return new ToolCallResult(callId, toolName, false, resultJson, null, errorCode, errorMessage, false);
    }
}
