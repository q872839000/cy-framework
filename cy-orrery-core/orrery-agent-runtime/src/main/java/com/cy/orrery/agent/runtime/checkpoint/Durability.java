// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint;

/**
 * 检查点落盘时机（对标 LangGraph 的 durability 三档）。
 * <p>
 * 语义对齐：
 * <ul>
 *     <li>{@link #SYNC} —— 每个步骤结束（STEP_FINISHED）同步落盘，最安全最慢，
 *         崩溃后可从最近步骤精确恢复；</li>
 *     <li>{@link #ASYNC} —— 每个步骤结束异步落盘（快照在引擎线程内同步完成，
 *         写存储走后台线程），崩溃可能丢最后一个检查点，默认均衡档；</li>
 *     <li>{@link #EXIT} —— 运行期不按步骤落盘，仅在挂起（审批 / 澄清 / 阶段等待）
 *         时落盘，最快；运行中途崩溃不可按步骤恢复（无现场可回）。</li>
 * </ul>
 * 无论哪一档：挂起时必定落盘（这是 HITL 跨进程恢复的底线）；终态<b>不落盘</b>——
 * 由 AgentRunner 直接清理检查点（运行结束不可续，终态结果经事件分发）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public enum Durability {

    /** 每步骤同步落盘。 */
    SYNC,

    /** 每步骤异步落盘（默认均衡档）。 */
    ASYNC,

    /** 仅挂起时落盘（终态清理、运行期不落）。 */
    EXIT
}
