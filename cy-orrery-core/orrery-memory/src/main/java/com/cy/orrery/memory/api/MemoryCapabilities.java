// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import lombok.Builder;
import lombok.Value;

/**
 * 记忆能力视图（消费方契约：启动后自检哪些子系统在线，据此决定功能面）。
 *
 * @author pcy
 */
@Value
@Builder
public class MemoryCapabilities {

    /** L0 上下文管理是否在线（恒定 true，仅摘要持久化依赖窗口存储）。 */
    boolean contextAvailable;

    /** L0 窗口是否可跨 run 恢复（stateful 模式且窗口存储在线）。 */
    boolean windowPersistent;

    /** 用户画像是否在线。 */
    boolean profileAvailable;

    /** L2 长期语义记忆是否在线。 */
    boolean semanticAvailable;

    /** L1 情景记忆是否在线。 */
    boolean episodicAvailable;

    /** LLM 对话端口是否可用（摘要/提取/决策的公共前置）。 */
    boolean chatAvailable;

    /** 向量化端口是否可用（语义召回的公共前置）。 */
    boolean embeddingAvailable;
}
