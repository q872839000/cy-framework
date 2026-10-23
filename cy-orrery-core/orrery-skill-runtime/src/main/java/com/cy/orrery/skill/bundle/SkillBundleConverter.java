// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.bundle;

import com.cy.orrery.protocol.enums.SkillInstallState;
import com.cy.orrery.protocol.enums.SkillKind;
import com.cy.orrery.protocol.enums.SkillOwnerType;
import com.cy.orrery.protocol.enums.SkillSource;
import com.cy.orrery.protocol.enums.SkillVisibility;
import com.cy.orrery.protocol.model.SkillDescriptor;
import com.cy.orrery.skill.registry.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * SkillBundle → SkillDescriptor 转换器
 * <p>
 * 将从 SKILL.md 目录 bundle 扫描得到的 {@link SkillBundle} 转换为平台协议层的
 * {@link SkillDescriptor}，使其可以直接注册到 {@link SkillRegistry}。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Slf4j
public class SkillBundleConverter {

    private final ObjectMapper objectMapper;

    /**
     * 构造转换器。
     *
     * @param objectMapper JSON 编解码器（schema 摘要提取）
     */
    public SkillBundleConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 构造转换器（自建 JSON 编解码器）。
     */
    public SkillBundleConverter() {
        this(new ObjectMapper());
    }

    /**
     * 将 SkillBundle 转换为 SkillDescriptor
     *
     * @param bundle  Skill Bundle
     * @param ownerType 所属实体类型（Agent 或 Butler）
     * @param ownerId 所属实体ID（Agent ID 或 Butler ID）
     * @return SkillDescriptor
     */
    public SkillDescriptor convert(SkillBundle bundle, SkillOwnerType ownerType, String ownerId) {
        return SkillDescriptor.builder()
                .skillId(bundle.getSkillId())
                .name(bundle.getName())
                .version(bundle.getVersion())
                .description(bundle.getDescription())
                .summary(extractSummary(bundle))
                .kind(determineKind(bundle))
                .ownerType(ownerType)
                .ownerId(ownerId)
                .capabilities(bundle.getTags())
                .source(SkillSource.BUNDLED)
                .inputSchema(bundle.getInputSchema())
                .outputSchema(bundle.getOutputSchema())
                .inputModes(extractStringList(bundle.getFrontmatter(), "inputModes"))
                .outputModes(extractStringList(bundle.getFrontmatter(), "outputModes"))
                .examples(bundle.getExamples())
                .visibility(extractVisibility(bundle))
                .installState(SkillInstallState.INSTALLED)
                .build();
    }

    /**
     * 提取摘要：优先 frontmatter.summary，否则截取 description 前 120 字符
     */
    private String extractSummary(SkillBundle bundle) {
        Object summary = bundle.getFrontmatter() != null
                ? bundle.getFrontmatter().get("summary") : null;
        if (summary instanceof String s && !s.isBlank()) {
            return s;
        }
        if (bundle.getDescription() != null && !bundle.getDescription().isBlank()) {
            String desc = bundle.getDescription().strip();
            return desc.length() > 120 ? desc.substring(0, 120) + "..." : desc;
        }
        return "";
    }

    /**
     * 确定技能类型：frontmatter.kind 或根据 bundle 结构推断
     */
    private SkillKind determineKind(SkillBundle bundle) {
        Object kind = bundle.getFrontmatter() != null
                ? bundle.getFrontmatter().get("kind") : null;
        if (kind instanceof String s && !s.isBlank()) {
            SkillKind parsed = SkillKind.fromValue(s);
            if (parsed != null) return parsed;
        }
        // 如果有 instructions 但没有 schema，大概率是 prompt 类型
        if (bundle.getInstructions() != null && bundle.getInputSchema() == null) {
            return SkillKind.PROMPT;
        }
        return SkillKind.EXECUTABLE;
    }

    /**
     * 从 frontmatter 中提取字符串列表
     */
    @SuppressWarnings("unchecked")
    private java.util.List<String> extractStringList(Map<String, Object> frontmatter, String key) {
        if (frontmatter == null) {
            return null;
        }
        Object val = frontmatter.get(key);
        if (val instanceof java.util.List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return null;
    }

    /**
     * 提取可见性：默认 PUBLIC（bundle 包通常是公开技能）
     */
    private SkillVisibility extractVisibility(SkillBundle bundle) {
        Object vis = bundle.getFrontmatter() != null
                ? bundle.getFrontmatter().get("visibility") : null;
        if (vis instanceof String s) {
            try {
                return SkillVisibility.valueOf(s.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return SkillVisibility.PUBLIC;
    }
}
