// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

/**
 * 记忆归属范围（类型 + 属主 + 租户）。
 * <p>
 * 回答"这条记忆归谁、召回时谁能看到"。任何写入必须显式携带 scope；
 * TENANT 级属主固定为租户 ID 本身。
 * </p>
 *
 * @param type     归属类型
 * @param ownerId  属主 ID（userId / agentId / sessionId / tenantId）
 * @param tenantId 租户 ID（可空 = 单租户部署）
 * @author pcy
 */
public record MemoryScope(ScopeType type, String ownerId, String tenantId) {

    /**
     * 构造归属范围。
     *
     * @throws IllegalArgumentException 类型或属主缺失
     */
    public MemoryScope {
        if (type == null) {
            throw new IllegalArgumentException("scope 类型不能为空");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("scope 属主不能为空: type=" + type);
        }
    }

    /**
     * 用户级归属。
     *
     * @param tenantId 租户 ID（可空）
     * @param userId   用户 ID
     * @return 用户级归属
     */
    public static MemoryScope ofUser(String tenantId, String userId) {
        return new MemoryScope(ScopeType.USER, userId, tenantId);
    }

    /**
     * Agent 级归属。
     *
     * @param tenantId 租户 ID（可空）
     * @param agentId  Agent ID
     * @return Agent 级归属
     */
    public static MemoryScope ofAgent(String tenantId, String agentId) {
        return new MemoryScope(ScopeType.AGENT, agentId, tenantId);
    }

    /**
     * 会话级归属。
     *
     * @param tenantId  租户 ID（可空）
     * @param sessionId 会话 ID
     * @return 会话级归属
     */
    public static MemoryScope ofSession(String tenantId, String sessionId) {
        return new MemoryScope(ScopeType.SESSION, sessionId, tenantId);
    }

    /**
     * 租户级归属（属主 = 租户 ID）。
     *
     * @param tenantId 租户 ID
     * @return 租户级归属
     */
    public static MemoryScope ofTenant(String tenantId) {
        return new MemoryScope(ScopeType.TENANT, tenantId, tenantId);
    }
}
