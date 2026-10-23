// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.admin;

import com.cy.core.exception.CYException;
import com.cy.orrery.memory.api.MemoryItem;
import com.cy.orrery.memory.api.MemoryQuery;
import com.cy.orrery.memory.api.MemoryRevision;
import com.cy.orrery.memory.api.Provenance;
import com.cy.orrery.memory.profile.ProfileDimension;
import com.cy.orrery.memory.profile.ProfileItem;
import com.cy.orrery.memory.profile.ProfileService;
import com.cy.orrery.memory.semantic.SemanticMemoryService;
import java.util.List;

/**
 * 记忆管理门面（管理端/控制台用：查询、审计、人工更正、作废）。
 * <p>
 * 各子系统未装配时对应方法抛 50601（能力未装配）。
 * </p>
 *
 * @author pcy
 */
public class MemoryAdminService {

    /** 语义记忆服务（可空 = 未装配）。 */
    private final SemanticMemoryService semanticService;

    /** 画像服务（可空 = 未装配）。 */
    private final ProfileService profileService;

    /**
     * 构造管理门面（子系统服务可空，未装配时对应方法抛 50601）。
     *
     * @param semanticService 语义记忆服务（可空）
     * @param profileService  画像服务（可空）
     */
    public MemoryAdminService(SemanticMemoryService semanticService,
                              ProfileService profileService) {
        this.semanticService = semanticService;
        this.profileService = profileService;
    }

    /**
     * 长期记忆结构化查询。
     *
     * @param query 查询条件
     * @return 条目列表
     */
    public List<MemoryItem> listMemories(MemoryQuery query) {
        return requireSemantic().list(query);
    }

    /**
     * 审计链查询。
     *
     * @param memoryId 记忆 ID
     * @return 审计链
     */
    public List<MemoryRevision> history(String memoryId) {
        return requireSemantic().history(memoryId);
    }

    /**
     * 人工更正记忆。
     *
     * @param memoryId   记忆 ID
     * @param newContent 新正文
     * @param actorId    操作者
     * @return 更新后的条目
     */
    public MemoryItem correct(String memoryId, String newContent, String actorId) {
        return requireSemantic().correct(memoryId, newContent, null,
                new Provenance(actorId, null, "管理端人工更正", true));
    }

    /**
     * 作废记忆。
     *
     * @param memoryId 记忆 ID
     * @param actorId  操作者
     * @param reason   依据
     */
    public void invalidate(String memoryId, String actorId, String reason) {
        requireSemantic().invalidate(memoryId, actorId + ": " + reason);
    }

    /**
     * 画像查询（仅 ACTIVE，运行侧语义）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param dimension 维度（null = 全部）
     * @return ACTIVE 条目
     */
    public List<ProfileItem> listProfileItems(ProfileItem.OwnerType ownerType, String tenantId,
                                              String ownerId, String dimension) {
        return requireProfile().getItems(ownerType, tenantId, ownerId, dimension);
    }

    /**
     * 画像全状态查询（管理端透明展示：含 SUPERSEDED/INVALIDATED）。
     *
     * @param ownerType 属主类型
     * @param tenantId  租户 ID（可空）
     * @param ownerId   属主 ID
     * @param dimension 维度（null = 全部）
     * @return 全部条目
     */
    public List<ProfileItem> listAllProfileItems(ProfileItem.OwnerType ownerType, String tenantId,
                                                 String ownerId, String dimension) {
        return requireProfile().listAll(ownerType, tenantId, ownerId, dimension);
    }

    /**
     * 已注册画像维度（管理端渲染 label 用）。
     *
     * @return 维度列表
     */
    public List<ProfileDimension> profileDimensions() {
        return requireProfile().dimensions();
    }

    /**
     * 作废画像条目。
     *
     * @param itemId 条目 ID
     */
    public void invalidateProfileItem(String itemId) {
        requireProfile().invalidate(itemId);
    }

    /**
     * 编辑画像条目（旧条目作废，同维度新值显式生效）。
     *
     * @param itemId   条目 ID
     * @param newValue 新值
     * @param actorId  操作者
     * @return 新条目（仲裁拒绝返回 null）
     */
    public ProfileItem editProfileItem(String itemId, String newValue, String actorId) {
        return requireProfile().edit(itemId, newValue, "管理端人工编辑: " + actorId);
    }

    /**
     * 画像条目谱系（同维度全状态，审计展示）。
     *
     * @param itemId 条目 ID
     * @return 谱系列表
     */
    public List<ProfileItem> profileItemLineage(String itemId) {
        return requireProfile().lineage(itemId);
    }

    /** 语义服务存在性断言。 */
    private SemanticMemoryService requireSemantic() {
        if (semanticService == null) {
            throw new CYException(50601, "semantic");
        }
        return semanticService;
    }

    /** 画像服务存在性断言。 */
    private ProfileService requireProfile() {
        if (profileService == null) {
            throw new CYException(50601, "profile");
        }
        return profileService;
    }
}
