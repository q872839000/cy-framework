// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时事件工厂：统一事件 data key 契约。
 * <p>
 * 引擎与 harness 的全部事件发射点收敛于此，保证同类事件的 data key 拼写与结构稳定——
 * 宿主桥接 wire 协议、做逐 seq 事件流对比时依赖该契约不漂移。
 * 禁止在引擎 / harness 里手写 emit(type, map) 绕过工厂（契约冻结）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public final class Events {

    private Events() {
    }

    /**
     * 步骤开始事件。
     *
     * @param bus       事件总线
     * @param stepIndex 步骤序号
     * @return 已发射事件
     */
    public static RuntimeEvent stepStarted(RuntimeEventBus bus, int stepIndex) {
        return bus.emit(RuntimeEventType.STEP_STARTED, Map.of("stepIndex", stepIndex));
    }

    /**
     * 步骤开始事件（带扩展属性，如重放标记）。
     *
     * @param bus       事件总线
     * @param stepIndex 步骤序号
     * @param extras    扩展属性
     * @return 已发射事件
     */
    public static RuntimeEvent stepStarted(RuntimeEventBus bus, int stepIndex, Map<String, Object> extras) {
        Map<String, Object> data = new HashMap<>(extras == null ? Map.of() : extras);
        data.put("stepIndex", stepIndex);
        return bus.emit(RuntimeEventType.STEP_STARTED, data);
    }

    /**
     * 步骤结束事件。
     *
     * @param bus          事件总线
     * @param stepIndex    步骤序号
     * @param hadToolCalls 本步骤是否含工具调用
     * @return 已发射事件
     */
    public static RuntimeEvent stepFinished(RuntimeEventBus bus, int stepIndex, boolean hadToolCalls) {
        return bus.emit(RuntimeEventType.STEP_FINISHED, Map.of(
                "stepIndex", stepIndex, "hadToolCalls", hadToolCalls));
    }

    /**
     * 步骤结束事件（带扩展属性）。
     *
     * @param bus          事件总线
     * @param stepIndex    步骤序号
     * @param hadToolCalls 本步骤是否含工具调用
     * @param extras       扩展属性
     * @return 已发射事件
     */
    public static RuntimeEvent stepFinished(RuntimeEventBus bus, int stepIndex, boolean hadToolCalls,
                                            Map<String, Object> extras) {
        Map<String, Object> data = new HashMap<>(extras == null ? Map.of() : extras);
        data.put("stepIndex", stepIndex);
        data.put("hadToolCalls", hadToolCalls);
        return bus.emit(RuntimeEventType.STEP_FINISHED, data);
    }

    /**
     * 工具调用开始事件（发射权归工具 harness，见 ToolInvoker 契约）。
     *
     * @param bus        事件总线
     * @param toolCallId 调用 ID
     * @param toolName   工具名
     * @return 已发射事件
     */
    public static RuntimeEvent toolStarted(RuntimeEventBus bus, String toolCallId, String toolName) {
        return bus.emit(RuntimeEventType.TOOL_STARTED, Map.of(
                "toolCallId", toolCallId, "toolName", toolName));
    }

    /**
     * 工具调用结束事件（发射权归工具 harness，见 ToolInvoker 契约）。
     *
     * @param bus         事件总线
     * @param toolCallId  调用 ID
     * @param toolName    工具名
     * @param durationMs  耗时毫秒
     * @param success     是否成功
     * @param errorCode   失败错误码（成功为 null）
     * @return 已发射事件
     */
    public static RuntimeEvent toolFinished(RuntimeEventBus bus, String toolCallId, String toolName,
                                            long durationMs, boolean success, String errorCode) {
        Map<String, Object> data = new HashMap<>();
        data.put("toolCallId", toolCallId);
        data.put("toolName", toolName);
        data.put("durationMs", durationMs);
        data.put("success", success);
        if (errorCode != null) {
            data.put("errorCode", errorCode);
        }
        return bus.emit(RuntimeEventType.TOOL_FINISHED, data);
    }

    /**
     * 业务级错误事件（非终态提示）。
     *
     * @param bus       事件总线
     * @param code      业务错误码
     * @param message   错误消息
     * @param retryable 是否可重试
     * @return 已发射事件
     */
    public static RuntimeEvent businessError(RuntimeEventBus bus, String code, String message,
                                             boolean retryable) {
        return bus.emit(RuntimeEventType.BUSINESS_ERROR, Map.of(
                "code", code, "message", message, "retryable", retryable));
    }

    /**
     * 澄清事件。
     *
     * @param bus      事件总线
     * @param question 澄清问题
     * @param options  候选选项
     * @return 已发射事件
     */
    public static RuntimeEvent clarification(RuntimeEventBus bus, String question, List<String> options) {
        return bus.emit(RuntimeEventType.CLARIFICATION, Map.of(
                "question", question,
                "options", options == null ? List.of() : options));
    }

    /**
     * 审批请求事件（默认实现发射；宿主接管审批协调器后由宿主发射，见 ToolRoundHooks#onApprovalBarrier）。
     *
     * @param bus           事件总线
     * @param approvalId    审批单 ID
     * @param summary       审批摘要
     * @param callId        触发审批的调用 ID
     * @param affectedKeys  受影响的业务对象键
     * @return 已发射事件
     */
    public static RuntimeEvent approvalRequired(RuntimeEventBus bus, String approvalId, String summary,
                                                String callId, List<String> affectedKeys) {
        return bus.emit(RuntimeEventType.APPROVAL_REQUIRED, Map.of(
                "approvalId", approvalId,
                "summary", summary == null ? "" : summary,
                "callId", callId,
                "affectedKeys", affectedKeys == null ? List.of() : affectedKeys));
    }

    /**
     * 系统追踪事件。
     *
     * @param bus     事件总线
     * @param kind    追踪类别（如 context.compacted / agent.idle_fuse）
     * @param message 追踪消息
     * @return 已发射事件
     */
    public static RuntimeEvent systemTrace(RuntimeEventBus bus, String kind, String message) {
        return bus.emit(RuntimeEventType.SYSTEM_TRACE, Map.of("kind", kind, "message", message));
    }

    /**
     * 系统追踪事件（带扩展属性）。
     *
     * @param bus     事件总线
     * @param kind    追踪类别
     * @param message 追踪消息
     * @param attrs   扩展属性
     * @return 已发射事件
     */
    public static RuntimeEvent systemTrace(RuntimeEventBus bus, String kind, String message,
                                           Map<String, Object> attrs) {
        Map<String, Object> data = new HashMap<>(attrs == null ? Map.of() : attrs);
        data.put("kind", kind);
        data.put("message", message);
        return bus.emit(RuntimeEventType.SYSTEM_TRACE, data);
    }

    /**
     * 里程碑事件。
     *
     * @param bus   事件总线
     * @param title 里程碑标题
     * @return 已发射事件
     */
    public static RuntimeEvent milestone(RuntimeEventBus bus, String title) {
        return bus.emit(RuntimeEventType.MILESTONE, Map.of("title", title));
    }

    /**
     * 流式正文增量（正文块，kind=text）。
     *
     * @param bus       事件总线
     * @param stepIndex 所属步骤序号
     * @param delta     本次增量文本
     * @return 已发射事件
     */
    public static RuntimeEvent textDelta(RuntimeEventBus bus, int stepIndex, String delta) {
        return textDelta(bus, stepIndex, delta, "text");
    }

    /**
     * 流式正文增量（按块种类区分正文 / 深度思考）。
     *
     * @param bus       事件总线
     * @param stepIndex 所属步骤序号
     * @param delta     本次增量文本
     * @param kind      块种类：text（正文）/ reasoning（深度思考）
     * @return 已发射事件
     */
    public static RuntimeEvent textDelta(RuntimeEventBus bus, int stepIndex, String delta, String kind) {
        return textDelta(bus, stepIndex, delta, kind, null);
    }

    /**
     * 流式正文增量（带块标签：同一轮内多段思考 / 正文按业务阶段标注）。
     *
     * @param bus       事件总线
     * @param stepIndex 所属步骤序号
     * @param delta     本次增量文本
     * @param kind      块种类：text（正文）/ reasoning（深度思考）
     * @param label     块标签（如"思考·战术意图"）；无标签传 null
     * @return 已发射事件
     */
    public static RuntimeEvent textDelta(RuntimeEventBus bus, int stepIndex, String delta, String kind,
                                         String label) {
        Map<String, Object> data = new HashMap<>();
        data.put("stepIndex", stepIndex);
        data.put("delta", delta == null ? "" : delta);
        data.put("kind", kind == null ? "text" : kind);
        if (label != null && !label.isBlank()) {
            data.put("label", label);
        }
        return bus.emit(RuntimeEventType.TEXT_DELTA, data);
    }

    /**
     * LLM 调用遥测事件（调用结束后的 token 用量 / 耗时 / 结束原因）。
     *
     * @param bus              事件总线
     * @param callId           调用 ID
     * @param model            模型名（可空）
     * @param promptTokens     提示 token 数
     * @param completionTokens 补全 token 数
     * @param latencyMs        调用耗时毫秒
     * @param finishReason     生成结束原因（厂商漏发时为 null）
     * @return 已发射事件
     */
    public static RuntimeEvent llmCall(RuntimeEventBus bus, String callId, String model,
                                       int promptTokens, int completionTokens, long latencyMs,
                                       String finishReason) {
        Map<String, Object> data = new HashMap<>();
        data.put("callId", callId);
        data.put("model", model == null ? "" : model);
        data.put("promptTokens", promptTokens);
        data.put("completionTokens", completionTokens);
        data.put("latencyMs", latencyMs);
        if (finishReason != null) {
            data.put("finishReason", finishReason);
        }
        return bus.emit(RuntimeEventType.LLM_CALL, data);
    }

    /**
     * 运行开始事件（由运行门面在创建运行上下文时发射，引擎不发）。
     *
     * @param bus 事件总线
     * @return 已发射事件
     */
    public static RuntimeEvent runStarted(RuntimeEventBus bus) {
        return bus.emit(RuntimeEventType.RUN_STARTED);
    }

    /**
     * 运行正常完成事件（终态；由运行门面发射）。
     *
     * @param bus       事件总线
     * @param telemetry 运行遥测（rounds / toolCalls / promptTokens / completionTokens 等）
     * @return 已发射事件
     */
    public static RuntimeEvent runCompleted(RuntimeEventBus bus, Map<String, Object> telemetry) {
        return bus.emit(RuntimeEventType.RUN_COMPLETED,
                telemetry == null ? Map.of() : telemetry);
    }

    /**
     * 运行失败事件（终态；由运行门面发射）。
     *
     * @param bus       事件总线
     * @param code      失败错误码
     * @param message   失败消息
     * @param retryable 是否可重试
     * @return 已发射事件
     */
    public static RuntimeEvent runFailed(RuntimeEventBus bus, String code, String message,
                                         boolean retryable) {
        return bus.emit(RuntimeEventType.RUN_FAILED, Map.of(
                "code", code, "message", message == null ? "" : message,
                "retryable", retryable));
    }

    /**
     * 运行取消事件（终态；由运行门面发射）。
     *
     * @param bus    事件总线
     * @param reason 取消原因
     * @param phase  取消发生时的阶段标识
     * @return 已发射事件
     */
    public static RuntimeEvent cancelled(RuntimeEventBus bus, String reason, String phase) {
        return bus.emit(RuntimeEventType.CANCELLED, Map.of(
                "reason", reason == null ? "" : reason,
                "phase", phase == null ? "" : phase));
    }
}
