// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.util.Locale;

/**
 * 长期记忆内容类型（三值收敛）。
 *
 * @author pcy
 */
public enum MemoryKind {

    /** 事实：关于用户、业务对象或环境的客观事实。 */
    FACT,

    /** 偏好：用户或 Agent 的偏好与习惯。 */
    PREFERENCE,

    /** 经验：完成某类任务被验证有效（或无效）的做法。 */
    EXPERIENCE,

    /** 情景：L1 情景卡片的向量标记（仅索引过滤用，L2 提取不产出该类型）。 */
    EPISODE;

    /**
     * 文本解析（大小写不敏感，未知值与空白归 FACT——LLM 输出与外部入参的容错入口）。
     *
     * @param raw 原始文本（可空）
     * @return 记忆类型
     */
    public static MemoryKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return FACT;
        }
        try {
            return MemoryKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FACT;
        }
    }
}
