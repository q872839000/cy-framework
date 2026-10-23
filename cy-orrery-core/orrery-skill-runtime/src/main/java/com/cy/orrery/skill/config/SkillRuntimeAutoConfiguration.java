// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.config;

import com.cy.orrery.skill.bundle.SkillBundleConverter;
import com.cy.orrery.skill.bundle.SkillBundleScanner;
import com.cy.orrery.skill.executor.SkillExecutor;
import com.cy.orrery.skill.loader.SkillLoader;
import com.cy.orrery.skill.prompt.PromptSkillLoader;
import com.cy.orrery.skill.prompt.PromptSkillRegistry;
import com.cy.orrery.skill.registry.SkillRegistry;
import com.cy.orrery.skill.workspace.WorkspaceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Skill Runtime 自动配置
 * <p>
 * 为引入 orrery-skill-runtime 的服务自动装配核心组件：
 * <ul>
 *   <li><b>ExecutableSkill 体系</b>：SkillRegistry、SkillLoader、SkillExecutor</li>
 *   <li><b>PromptSkill 体系</b>：PromptSkillRegistry、PromptSkillLoader
 *       （对齐 Anthropic SKILL.md / OpenClaw / ClawHub 生态）</li>
 *   <li><b>基础设施</b>：BundleScanner、BundleConverter、WorkspaceParser</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@AutoConfiguration
public class SkillRuntimeAutoConfiguration {

    // ==================== ExecutableSkill 体系 ====================

    /** 技能注册表。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry() {
        return new SkillRegistry();
    }

    /** Bundle 目录扫描器。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillBundleScanner skillBundleScanner(ObjectMapper objectMapper) {
        return new SkillBundleScanner(objectMapper);
    }

    /** Bundle → SkillDescriptor 转换器。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillBundleConverter skillBundleConverter(ObjectMapper objectMapper) {
        return new SkillBundleConverter(objectMapper);
    }

    /** 技能加载器（SPI / Bean / Bundle 三通道）。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillLoader skillLoader(SkillRegistry skillRegistry,
                                   SkillBundleScanner bundleScanner,
                                   SkillBundleConverter bundleConverter) {
        return new SkillLoader(skillRegistry, bundleScanner, bundleConverter);
    }

    /** 技能执行器。 */
    @Bean
    @ConditionalOnMissingBean
    public SkillExecutor skillExecutor(SkillRegistry skillRegistry) {
        return new SkillExecutor(skillRegistry);
    }

    // ==================== PromptSkill 体系（对齐 Anthropic/OpenClaw 生态） ====================

    /**
     * 提示词技能注册表（Butler 的 PlannerPromptManager 从此取可注入 prompt 的 Skill 列表）。
     */
    @Bean
    @ConditionalOnMissingBean
    public PromptSkillRegistry promptSkillRegistry() {
        return new PromptSkillRegistry();
    }

    /** 提示词技能加载器（SKILL.md frontmatter + Markdown 正文解析）。 */
    @Bean
    @ConditionalOnMissingBean
    public PromptSkillLoader promptSkillLoader() {
        return new PromptSkillLoader();
    }

    // ==================== 基础设施 ====================

    /** Workspace 层文件解析器。 */
    @Bean
    @ConditionalOnMissingBean
    public WorkspaceParser workspaceParser() {
        return new WorkspaceParser();
    }
}
