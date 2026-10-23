// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.hitl;

/**
 * 挂起原因。
 *
 * @author pcy
 * @since 2026/08/19
 */
public enum SuspensionReason {

    /** 工具触发审批屏障（NEEDS_APPROVAL）。 */
    NEEDS_APPROVAL,

    /** 工具触发澄清（NEEDS_CLARIFICATION）。 */
    NEEDS_CLARIFICATION,

    /** 执行计划待整体确认（NEEDS_PLAN_CONFIRMATION）。 */
    NEEDS_PLAN_CONFIRMATION,

    /** 阶段链等待阶段级确认（Awaiting）。 */
    AWAITING_STAGE
}
