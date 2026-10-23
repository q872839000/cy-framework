// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.loader;

import com.cy.orrery.protocol.enums.SkillInstallState;
import com.cy.orrery.protocol.enums.SkillOwnerType;
import com.cy.orrery.protocol.model.SkillDescriptor;
import com.cy.orrery.skill.bundle.SkillBundle;
import com.cy.orrery.skill.bundle.SkillBundleConverter;
import com.cy.orrery.skill.bundle.SkillBundleScanner;
import com.cy.orrery.skill.registry.SkillRegistry;
import com.cy.orrery.skill.spi.Skill;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 技能加载器
 * <p>
 * 负责从多种来源发现并加载 Skill。支持三种发现方式：
 * <ul>
 *   <li>Java SPI — 通过 {@code META-INF/services/com.cy.orrery.skill.spi.Skill} 自动发现</li>
 *   <li>Spring Bean — 外部代码传入 Bean 列表批量注册</li>
 *   <li>Bundle 目录 — 扫描 SKILL.md bundle 目录，解析 frontmatter 注册为声明式 Skill</li>
 * </ul>
 * 加载完成后自动注册到 {@link SkillRegistry}，更新 Skill 安装状态。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Slf4j
public class SkillLoader {

    private final SkillRegistry skillRegistry;
    private final SkillBundleScanner bundleScanner;
    private final SkillBundleConverter bundleConverter;

    /**
     * 构造加载器（自建 Bundle 扫描器与转换器）。
     *
     * @param skillRegistry 技能注册表
     */
    public SkillLoader(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
        this.bundleScanner = new SkillBundleScanner();
        this.bundleConverter = new SkillBundleConverter();
    }

    /**
     * 构造加载器。
     *
     * @param skillRegistry   技能注册表
     * @param bundleScanner   Bundle 扫描器
     * @param bundleConverter Bundle 转换器
     */
    public SkillLoader(SkillRegistry skillRegistry,
                       SkillBundleScanner bundleScanner,
                       SkillBundleConverter bundleConverter) {
        this.skillRegistry = skillRegistry;
        this.bundleScanner = bundleScanner;
        this.bundleConverter = bundleConverter;
    }

    /**
     * 通过 Java SPI 发现并加载所有 Skill 实现（归属缺省 AGENT）
     *
     * @return 成功加载的 Skill 数量
     */
    public int discoverAndLoadAll() {
        return discoverAndLoadAll(SkillOwnerType.AGENT, null);
    }

    /**
     * 通过 Java SPI 发现并加载所有 Skill 实现。
     *
     * @param ownerType 属主类型
     * @param ownerId   属主 ID（可空）
     * @return 成功加载的 Skill 数量
     */
    public int discoverAndLoadAll(SkillOwnerType ownerType, String ownerId) {
        int count = 0;
        ServiceLoader<Skill> serviceLoader = ServiceLoader.load(Skill.class);
        for (Skill skill : serviceLoader) {
            try {
                loadAndRegister(skill, ownerType, ownerId);
                count++;
            } catch (RuntimeException e) {
                log.error("SPI Skill 加载失败: skillId={}", skill.getSkillId(), e);
            }
        }
        log.info("SPI Skill 发现完成: 成功加载 {} 个", count);
        return count;
    }

    /**
     * 从 Spring 容器中加载一批 Skill（由外部传入 Bean 列表，归属缺省 AGENT）
     *
     * @param skills Spring 容器中发现的 Skill Bean 列表
     * @return 成功加载的 Skill 数量
     */
    public int loadFromBeans(List<Skill> skills) {
        return loadFromBeans(skills, SkillOwnerType.AGENT, null);
    }

    /**
     * 从 Spring 容器中加载一批 Skill。
     *
     * @param skills    Skill Bean 列表
     * @param ownerType 属主类型
     * @param ownerId   属主 ID（可空）
     * @return 成功加载的 Skill 数量
     */
    public int loadFromBeans(List<Skill> skills, SkillOwnerType ownerType, String ownerId) {
        int count = 0;
        if (skills == null || skills.isEmpty()) {
            return count;
        }
        for (Skill skill : skills) {
            try {
                loadAndRegister(skill, ownerType, ownerId);
                count++;
            } catch (RuntimeException e) {
                log.error("Bean Skill 加载失败: skillId={}", skill.getSkillId(), e);
            }
        }
        log.info("Bean Skill 加载完成: 成功加载 {} 个", count);
        return count;
    }

    /**
     * 加载并注册单个 Skill（归属缺省 AGENT）
     *
     * @param skill Skill 实例
     */
    public void loadAndRegister(Skill skill) {
        loadAndRegister(skill, SkillOwnerType.AGENT, null);
    }

    /**
     * 加载并注册单个 Skill。
     *
     * @param skill     Skill 实例
     * @param ownerType 属主类型（null 归 AGENT）
     * @param ownerId   属主 ID（可空）
     */
    public void loadAndRegister(Skill skill, SkillOwnerType ownerType, String ownerId) {
        if (skill == null) {
            throw new IllegalArgumentException("Skill 实例不能为空");
        }
        if (skillRegistry.contains(skill.getSkillId())) {
            log.warn("Skill 已注册，跳过重复加载: skillId={}", skill.getSkillId());
            return;
        }

        SkillDescriptor descriptor = SkillDescriptor.builder()
                .skillId(skill.getSkillId())
                .name(skill.getName())
                .version(skill.getVersion())
                .summary(skill.getSummary())
                .ownerType(ownerType != null ? ownerType : SkillOwnerType.AGENT)
                .ownerId(ownerId)
                .installState(SkillInstallState.INSTALLING)
                .build();

        skillRegistry.register(skill, descriptor);
        log.info("Skill 加载并注册: skillId={}, name={}", skill.getSkillId(), skill.getName());
    }

    /**
     * 从 bundle 目录加载声明式 Skill
     * <p>
     * 扫描指定目录下的 SKILL.md bundle 子目录，解析 frontmatter 为 SkillDescriptor，
     * 注册到 SkillRegistry。Bundle Skill 没有 SPI 实例，仅有描述符（prompt / 声明类型）。
     * </p>
     *
     * @param skillsDir bundle 目录路径（如 ~/.orrery/skills/ 或 Agent 本地 skills/）
     * @param ownerId   所属实体ID（Agent ID 或 Butler ID）
     * @return 成功加载的 Skill 数量
     */
    public int loadFromBundles(Path skillsDir, String ownerId) {
        return loadFromBundles(skillsDir, SkillOwnerType.AGENT, ownerId);
    }

    public int loadFromBundles(Path skillsDir, SkillOwnerType ownerType, String ownerId) {
        int count = 0;
        List<SkillBundle> bundles = bundleScanner.scan(skillsDir);
        for (SkillBundle bundle : bundles) {
            try {
                if (skillRegistry.contains(bundle.getSkillId())) {
                    log.warn("Bundle Skill 已注册，跳过: skillId={}", bundle.getSkillId());
                    continue;
                }
                SkillDescriptor descriptor = bundleConverter.convert(
                        bundle,
                        ownerType != null ? ownerType : SkillOwnerType.AGENT,
                        ownerId);
                skillRegistry.registerDescriptor(descriptor);
                count++;
            } catch (RuntimeException e) {
                log.error("Bundle Skill 加载失败: skillId={}, 错误: {}",
                        bundle.getSkillId(), e.getMessage(), e);
            }
        }
        log.info("Bundle Skill 加载完成: dir={}, 成功 {} 个", skillsDir, count);
        return count;
    }

    /**
     * 卸载指定 Skill
     *
     * @param skillId 技能ID
     */
    public void unload(String skillId) {
        if (skillRegistry.contains(skillId)) {
            skillRegistry.unregister(skillId);
            log.info("Skill 已卸载: skillId={}", skillId);
        } else {
            log.warn("Skill 未注册，无法卸载: skillId={}", skillId);
        }
    }
}
