// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.admin;

import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryStatus;
import com.cy.orrery.memory.api.ScopeType;
import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 遗忘引擎（被遗忘权端到端：长期记忆 + 画像跨存储联动物理擦除）。
 * <p>
 * 擦除穿透能力开关——能力下线后的存量数据仍可被合规擦除。
 * 上下文窗口与情景卡片按 session 过期自消，不在用户级擦除面内。
 * </p>
 *
 * @author pcy
 */
public class ForgettingEngine {

    private static final Logger log = LoggerFactory.getLogger(ForgettingEngine.class);

    /** 语义记忆服务（可空 = 未装配）。 */
    private final SemanticMemoryService semanticService;

    /** 画像服务（可空 = 未装配）。 */
    private final ProfileService profileService;

    /**
     * 构造遗忘引擎（子系统服务可空，未装配时对应擦除面跳过）。
     *
     * @param semanticService 语义记忆服务（可空）
     * @param profileService  画像服务（可空）
     */
    public ForgettingEngine(SemanticMemoryService semanticService, ProfileService profileService) {
        this.semanticService = semanticService;
        this.profileService = profileService;
    }

    /**
     * 擦除报告。
     *
     * @param memoriesErased 长期记忆擦除条数
     * @param profileErased  画像条目擦除条数
     */
    public record ErasureReport(int memoriesErased, int profileErased) {
    }

    /**
     * 用户级物理擦除（长期记忆逐条走擦除全审计路径；画像整档物理删除）。
     *
     * @param tenantId 租户 ID（可空）
     * @param userId   用户 ID
     * @param actorId  操作者
     * @param reason   擦除依据
     * @return 擦除报告
     */
    public ErasureReport eraseUser(String tenantId, String userId, String actorId, String reason) {
        return eraseOwner(ScopeType.USER, ProfileItem.OwnerType.USER, tenantId, userId, actorId, reason);
    }

    /**
     * Agent 级物理擦除。
     *
     * @param tenantId 租户 ID（可空）
     * @param agentId  Agent ID
     * @param actorId  操作者
     * @param reason   擦除依据
     * @return 擦除报告
     */
    public ErasureReport eraseAgent(String tenantId, String agentId, String actorId, String reason) {
        return eraseOwner(ScopeType.AGENT, ProfileItem.OwnerType.AGENT, tenantId, agentId,
                actorId, reason);
    }

    /** 属主级物理擦除（长期记忆逐条审计擦除 + 画像整档删除）。 */
    private ErasureReport eraseOwner(ScopeType scopeType, ProfileItem.OwnerType ownerType,
                                     String tenantId, String ownerId, String actorId, String reason) {
        int memories = 0;
        if (semanticService != null) {
            List<MemoryItem> items = semanticService.list(MemoryQuery.builder()
                    .tenantId(tenantId).scopeType(scopeType).ownerId(ownerId)
                    .statuses(Set.of(MemoryStatus.values()))
                    .limit(MemoryQuery.MAX_LIMIT)
                    .build());
            for (MemoryItem item : items) {
                semanticService.erase(item.getMemoryId(), actorId, reason);
                memories++;
            }
        }
        int profile = 0;
        if (profileService != null) {
            profile = profileService.erase(ownerType, tenantId, ownerId);
        }
        log.info("记忆已擦除: scope={}/{}, memories={}, profileItems={}, actor={}",
                scopeType, ownerId, memories, profile, actorId);
        return new ErasureReport(memories, profile);
    }
}
