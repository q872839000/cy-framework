// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.registry;

import com.cy.orrery.protocol.enums.SkillInstallState;
import com.cy.orrery.protocol.model.SkillDescriptor;
import com.cy.orrery.skill.spi.Skill;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表
 * <p>
 * 维护当前 Agent 或 Butler 内部所有已加载 Skill 的注册信息。
 * SkillLoader 发现并加载 Skill 后注册到此表，SkillExecutor 据此查找并执行 Skill。
 * 线程安全，支持运行时动态注册和注销。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class SkillRegistry {

    /**
     * 已注册的 Skill 实例映射（skillId → Skill）
     */
    private final Map<String, Skill> skillInstances = new ConcurrentHashMap<>();

    /**
     * Skill 描述符映射（skillId → SkillDescriptor）
     */
    private final Map<String, SkillDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * 注册 Skill
     *
     * @param skill      Skill 实例
     * @param descriptor Skill 描述符
     */
    public void register(Skill skill, SkillDescriptor descriptor) {
        if (skill == null || descriptor == null) {
            throw new IllegalArgumentException("Skill 和 descriptor 不能为空");
        }
        String skillId = skill.getSkillId();
        skillInstances.put(skillId, skill);
        descriptor.setInstallState(SkillInstallState.INSTALLED);
        descriptors.put(skillId, descriptor);
        log.info("Skill 注册成功: skillId={}, name={}", skillId, skill.getName());
    }

    /**
     * 仅注册描述符（无 SPI 实例，用于 bundle / prompt 类型的声明式 Skill）
     *
     * @param descriptor Skill 描述符
     */
    public void registerDescriptor(SkillDescriptor descriptor) {
        if (descriptor == null || descriptor.getSkillId() == null) {
            throw new IllegalArgumentException("descriptor 和 skillId 不能为空");
        }
        String skillId = descriptor.getSkillId();
        descriptor.setInstallState(SkillInstallState.INSTALLED);
        descriptors.put(skillId, descriptor);
        log.info("Skill 描述符注册: skillId={}, name={}, kind={}",
                skillId, descriptor.getName(), descriptor.getKind());
    }

    /**
     * 注销 Skill
     *
     * @param skillId 技能ID
     */
    public void unregister(String skillId) {
        skillInstances.remove(skillId);
        SkillDescriptor desc = descriptors.get(skillId);
        if (desc != null) {
            desc.setInstallState(SkillInstallState.UNINSTALLED);
        }
        descriptors.remove(skillId);
        log.info("Skill 注销: skillId={}", skillId);
    }

    /**
     * 根据 skillId 获取 Skill 实例
     *
     * @param skillId 技能ID
     * @return Skill 实例，不存在返回 null
     */
    public Skill getSkill(String skillId) {
        return skillInstances.get(skillId);
    }

    /**
     * 根据 skillId 获取描述符
     *
     * @param skillId 技能ID
     * @return 描述符，不存在返回 null
     */
    public SkillDescriptor getDescriptor(String skillId) {
        return descriptors.get(skillId);
    }

    /**
     * 获取所有已注册的 Skill 描述符
     *
     * @return 描述符列表
     */
    public List<SkillDescriptor> listDescriptors() {
        return new ArrayList<>(descriptors.values());
    }

    /**
     * 获取所有已注册的 Skill 实例
     *
     * @return Skill 列表
     */
    public List<Skill> listSkills() {
        return new ArrayList<>(skillInstances.values());
    }

    /**
     * 检查 Skill 是否已注册
     *
     * @param skillId 技能ID
     * @return 是否已注册
     */
    public boolean contains(String skillId) {
        return skillInstances.containsKey(skillId);
    }

    /**
     * 获取已注册 Skill 数量
     *
     * @return 数量
     */
    public int size() {
        return skillInstances.size();
    }
}
