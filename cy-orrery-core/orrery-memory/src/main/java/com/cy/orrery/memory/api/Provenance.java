// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * 记忆溯源（谁写入、依据是什么、证据在哪）。
 *
 * @param actorId  操作者 ID（用户 / agent / system）
 * @param sourceRef 来源指针（如 sessionId#runId，供回查原文）
 * @param reason   写入/变更依据（进审计链）
 * @param explicit 是否显式写入（用户/Agent 明确要求记住；false = 巩固通道推断）
 * @author pcy
 */
public record Provenance(String actorId, String sourceRef, String reason, boolean explicit) {

    /**
     * 用户显式写入。
     *
     * @param userId    用户 ID
     * @param sourceRef 来源指针
     * @return 溯源
     */
    public static Provenance userExplicit(String userId, String sourceRef) {
        return new Provenance(userId, sourceRef, "用户显式要求记住", true);
    }

    /**
     * 巩固通道提取写入（推断）。
     *
     * @param sourceRef 来源指针
     * @param reason    依据
     * @return 溯源
     */
    public static Provenance extracted(String sourceRef, String reason) {
        return new Provenance("memory-consolidation", sourceRef, reason, false);
    }

    /**
     * 系统写入。
     *
     * @param sourceRef 来源指针
     * @return 溯源
     */
    public static Provenance system(String sourceRef) {
        return new Provenance("system", sourceRef, "系统写入", false);
    }
}
