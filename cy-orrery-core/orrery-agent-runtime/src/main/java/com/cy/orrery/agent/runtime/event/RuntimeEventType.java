// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.event;

/**
 * Agent 运行时事件类型。
 * <p>
 * 领域无关的最小事件集：覆盖循环引擎与阶段链引擎在运行期需要对外表达的节点。
 * 宿主服务负责把 {@link RuntimeEvent} 适配为自己的对外事件协议
 * （如 orrery-protocol 的 wire 事件），运行时本身不感知 wire 协议。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public enum RuntimeEventType {

    /** 运行开始。 */
    RUN_STARTED,

    /** 单个步骤（一轮 LLM 调用 / 一次重放）开始。 */
    STEP_STARTED,

    /** 单个步骤结束。 */
    STEP_FINISHED,

    /** 单个工具调用开始。 */
    TOOL_STARTED,

    /** 单个工具调用结束。 */
    TOOL_FINISHED,

    /** 流式正文增量（由 LLM 适配器在流式接收期间发射）。 */
    TEXT_DELTA,

    /** 单次 LLM 调用遥测（调用结束后的 token 用量 / 耗时 / 结束原因，调试频道）。 */
    LLM_CALL,

    /** 里程碑（阶段性成果，非终态）。 */
    MILESTONE,

    /** 系统级追踪信息（熔断触发、上下文压缩、阶段跳转等）。 */
    SYSTEM_TRACE,

    /** 需要用户澄清（非终态提示）。 */
    CLARIFICATION,

    /** 需要人工审批（挂起信号）。 */
    APPROVAL_REQUIRED,

    /** 业务级错误（非终态提示）。 */
    BUSINESS_ERROR,

    /** 运行正常完成（终态）。 */
    RUN_COMPLETED,

    /** 运行失败终止（终态）。 */
    RUN_FAILED,

    /** 运行被取消（终态）。 */
    CANCELLED;

    /**
     * 是否终态事件（终态事件之后不应再有同 runId 的事件）。
     *
     * @return true 表示终态
     */
    public boolean terminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED || this == CANCELLED;
    }
}
