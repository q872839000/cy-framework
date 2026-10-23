// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

import java.util.List;

/**
 * 澄清请求：工具结果检查器判定「需用户补充信息」时产出。
 * <p>
 * 引擎发 CLARIFICATION 事件并挂起循环（NEEDS_CLARIFICATION）；
 * 下一轮续跑语义由宿主澄清续跑协调器承担。
 * </p>
 *
 * @param question 澄清问题
 * @param options  候选选项（可空）
 * @author pcy
 * @since 2026/08/19
 */
public record ClarificationRequest(String question, List<String> options) {
}
