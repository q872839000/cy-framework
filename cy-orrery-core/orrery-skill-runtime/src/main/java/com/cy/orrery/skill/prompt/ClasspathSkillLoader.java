// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * classpath 提示词技能加载器：从 classpath 资源（含 jar 内）加载 PromptSkill。
 * <p>
 * {@link PromptSkillLoader} 只支持文件系统目录（{@code Files/Path}），而打包成 jar
 * 发布的 Agent 需要把技能作为 classpath 资源随包发布——本加载器补齐这一平台能力。
 * 目录组织约定与文件系统版一致（Anthropic Agent Skills 开放规范）：
 * </p>
 * <pre>
 * prompts/skills/
 *   deploy-scenario/        ← 技能目录，目录名即技能名（kebab-case）
 *     SKILL.md              ← 必需：frontmatter(name/description) + 正文
 *     filling.md            ← 可选：L3 附属资源，收进 {@link PromptSkill#getResources()}
 * </pre>
 * <p>
 * <b>解析语义唯一</b>：frontmatter 解析委托 {@link PromptSkillLoader#parseSkillMd}，
 * 本类只负责"classpath 扫描 + 按父目录分组 + 附属资源收集"——
 * classpath 下目录列举在 jar 包内并不可靠，按"文件全量扫描 + 按父路径分组"
 * 是唯一可移植做法。
 * </p>
 * <p>
 * 解析容错：单个技能损坏（缺 description / IO 失败）跳过该技能并记 warn，
 * 不影响其余技能加载。安全上限与文件系统版一致：单文件字节上限、单源技能数上限。
 * </p>
 *
 * @author pcy
 * @date 2026/08/14
 */
public class ClasspathSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(ClasspathSkillLoader.class);

    /** SKILL.md 文件名。 */
    private static final String SKILL_FILENAME = "SKILL.md";

    private final String locationPattern;
    private final PromptSkillLoader delegate;
    private final long maxSkillFileBytes;
    private final int maxSkillsPerSource;

    /**
     * 以默认安全上限构造加载器。
     *
     * @param locationPattern 资源扫描模式（如 {@code classpath*:prompts/skills/**}{@code /*.md}）
     */
    public ClasspathSkillLoader(String locationPattern) {
        this(locationPattern, new PromptSkillLoader(),
                PromptSkillLoader.DEFAULT_MAX_SKILL_FILE_BYTES,
                PromptSkillLoader.DEFAULT_MAX_SKILLS_PER_SOURCE);
    }

    /**
     * 构造加载器。
     *
     * @param locationPattern     资源扫描模式
     * @param delegate            frontmatter 解析委托（保证解析语义唯一）
     * @param maxSkillFileBytes   单文件最大字节数（≤0 取默认）
     * @param maxSkillsPerSource  单源最大技能数（≤0 取默认）
     */
    public ClasspathSkillLoader(String locationPattern, PromptSkillLoader delegate,
                                long maxSkillFileBytes, int maxSkillsPerSource) {
        this.locationPattern = locationPattern;
        this.delegate = delegate;
        this.maxSkillFileBytes = maxSkillFileBytes > 0
                ? maxSkillFileBytes : PromptSkillLoader.DEFAULT_MAX_SKILL_FILE_BYTES;
        this.maxSkillsPerSource = maxSkillsPerSource > 0
                ? maxSkillsPerSource : PromptSkillLoader.DEFAULT_MAX_SKILLS_PER_SOURCE;
    }

    /**
     * 扫描 classpath 并加载全部技能。
     *
     * @param source 来源标识（如 {@code "bundled"}）
     * @return 已加载的 PromptSkill 列表；扫描失败或无命中返回空列表
     */
    public List<PromptSkill> load(String source) {
        Map<String, List<Resource>> byDirectory = scanByDirectory();
        List<PromptSkill> skills = new ArrayList<>(byDirectory.size());
        for (Map.Entry<String, List<Resource>> entry : byDirectory.entrySet()) {
            if (skills.size() >= maxSkillsPerSource) {
                log.warn("Skill 数量超限，停止加载: pattern={}, loaded={}, max={}",
                        locationPattern, skills.size(), maxSkillsPerSource);
                break;
            }
            PromptSkill skill = parseSkillDirectory(entry.getKey(), entry.getValue(), source);
            if (skill != null) {
                skills.add(skill);
            }
        }
        log.info("Classpath PromptSkill 加载完成: pattern={}, source={}, count={}",
                locationPattern, source, skills.size());
        return skills;
    }

    /**
     * 扫描全部技能 Markdown 资源并按所属目录分组（目录路径 → 目录内文件）。
     * <p>
     * 一次扫描同时获得入口（SKILL.md）与 L3 附属资源（同目录其余 .md），
     * 避免按目录逐个二次解析。
     * </p>
     */
    private Map<String, List<Resource>> scanByDirectory() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException ex) {
            log.warn("扫描 classpath 技能资源失败 pattern={}: {}", locationPattern, ex.getMessage());
            return Map.of();
        }
        Map<String, List<Resource>> byDirectory = new TreeMap<>();
        for (Resource r : resources) {
            String parent = parentPathOf(r);
            if (parent == null) {
                log.warn("技能资源路径无法定位父目录，跳过: {}", r.getDescription());
                continue;
            }
            byDirectory.computeIfAbsent(parent, k -> new ArrayList<>()).add(r);
        }
        return byDirectory;
    }

    /**
     * 解析一个技能目录：读入口 SKILL.md 委托解析，其余 .md 收为 L3 资源。
     *
     * @param directory 目录路径（用于定位技能名与日志）
     * @param files     目录内全部 Markdown 资源
     * @param source    来源标识
     * @return 解析出的技能；入口缺失 / 入口读取失败时返回 {@code null}
     */
    private PromptSkill parseSkillDirectory(String directory, List<Resource> files, String source) {
        Resource entry = null;
        Map<String, String> resources = new LinkedHashMap<>();
        for (Resource r : files) {
            String filename = r.getFilename();
            if (filename == null) {
                continue;
            }
            if (SKILL_FILENAME.equalsIgnoreCase(filename)) {
                entry = r;
            } else {
                String content = readAll(r);
                if (content != null) {
                    resources.put(filename, content);
                }
            }
        }
        if (entry == null) {
            log.warn("技能目录缺少 {}，跳过整个目录: {}", SKILL_FILENAME, directory);
            return null;
        }
        String entryText = readAll(entry);
        if (entryText == null) {
            return null;
        }
        return delegate.parseSkillMd(entryText, directoryNameOf(directory),
                entryUriOf(entry), directory, source, resources);
    }

    /** 读取资源全文（UTF-8）；超限或失败时记 warn 并返回 {@code null}（调用方按跳过处理）。 */
    private String readAll(Resource r) {
        try {
            long size = r.contentLength();
            if (size > maxSkillFileBytes) {
                log.warn("技能资源文件过大，跳过: {}", r.getDescription());
                return null;
            }
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.warn("读取技能资源失败 {}: {}", r.getDescription(), ex.getMessage());
            return null;
        }
    }

    /** 取资源的父目录路径（去掉最后一个 {@code /} 之后的文件名部分）；无法定位时返回 {@code null}。 */
    private static String parentPathOf(Resource r) {
        try {
            String uri = r.getURI().toString();
            int slash = uri.lastIndexOf('/');
            return slash > 0 ? uri.substring(0, slash) : null;
        } catch (IOException ex) {
            return null;
        }
    }

    /** 取目录路径的末段作为技能名（kebab-case 目录名即技能名）。 */
    private static String directoryNameOf(String directory) {
        int slash = directory.lastIndexOf('/');
        return slash >= 0 ? directory.substring(slash + 1) : directory;
    }

    /** 取资源的 URI 字符串用于记录（不可解析时退化为 description）。 */
    private static String entryUriOf(Resource r) {
        try {
            return r.getURI().toString();
        } catch (IOException ex) {
            return r.getDescription();
        }
    }
}
