// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.prompt;

import com.cy.orrery.skill.util.FrontmatterParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 提示词技能加载器（对齐 Anthropic SKILL.md / OpenClaw / ClawHub 规范）
 * <p>
 * 扫描指定目录下的 SKILL.md 文件，解析 YAML frontmatter 和 Markdown 正文，
 * 构建 {@link PromptSkill} 实例列表。
 * </p>
 * <p>
 * <b>目录结构约定（兼容 OpenClaw / ClawHub / Anthropic）：</b>
 * <pre>
 * skills/
 *   github/
 *     SKILL.md          ← 主文件（必须存在）
 *     reference.md      ← 可选辅助文件
 *     scripts/helper.sh ← 可选脚本
 *   weather/
 *     SKILL.md
 *   ...
 * </pre>
 * 每个子目录代表一个 Skill，子目录下必须包含 {@code SKILL.md} 文件。
 * </p>
 * <p>
 * <b>安全限制（参考 OpenClaw）：</b>
 * <ul>
 *   <li>{@code maxSkillFileBytes} — 单个 SKILL.md 的最大字节数（防止巨大文件占满 prompt）</li>
 *   <li>{@code maxSkillsPerSource} — 单个目录源的最大 Skill 数量</li>
 *   <li>symlink 检测 — 确保 SKILL.md 不通过符号链接逃逸到目录外</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/03/21
 */
public class PromptSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptSkillLoader.class);

    /** SKILL.md 文件名。 */
    private static final String SKILL_FILENAME = "SKILL.md";

    /** 单个 SKILL.md 的默认最大字节数（256KB，对齐 OpenClaw DEFAULT_MAX_SKILL_FILE_BYTES）。 */
    public static final long DEFAULT_MAX_SKILL_FILE_BYTES = 256_000L;

    /** 单个目录源的默认最大 Skill 数量（对齐 OpenClaw DEFAULT_MAX_SKILLS_LOADED_PER_SOURCE）。 */
    public static final int DEFAULT_MAX_SKILLS_PER_SOURCE = 200;

    private final long maxSkillFileBytes;
    private final int maxSkillsPerSource;

    /**
     * 构造加载器（默认安全上限）。
     */
    public PromptSkillLoader() {
        this(DEFAULT_MAX_SKILL_FILE_BYTES, DEFAULT_MAX_SKILLS_PER_SOURCE);
    }

    /**
     * 构造加载器。
     *
     * @param maxSkillFileBytes  单文件最大字节数（≤0 取默认）
     * @param maxSkillsPerSource 单源最大技能数（≤0 取默认）
     */
    public PromptSkillLoader(long maxSkillFileBytes, int maxSkillsPerSource) {
        this.maxSkillFileBytes = maxSkillFileBytes > 0 ? maxSkillFileBytes : DEFAULT_MAX_SKILL_FILE_BYTES;
        this.maxSkillsPerSource = maxSkillsPerSource > 0 ? maxSkillsPerSource : DEFAULT_MAX_SKILLS_PER_SOURCE;
    }

    /**
     * 从指定目录加载所有 PromptSkill
     * <p>
     * 扫描 {@code skillsDir} 下所有直接子目录，查找包含 SKILL.md 的子目录，
     * 解析后构建 PromptSkill 实例。
     * </p>
     *
     * @param skillsDir 技能根目录（如 workspace/skills/）
     * @param source    来源标识（如 "bundled"、"workspace"、"installed"）
     * @return 已加载的 PromptSkill 列表
     */
    public List<PromptSkill> loadFromDirectory(Path skillsDir, String source) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            log.debug("Skill 目录不存在或无效: {}", skillsDir);
            return Collections.emptyList();
        }

        // 检查目录本身是否直接包含 SKILL.md（单 Skill 目录模式）
        Path rootSkillMd = skillsDir.resolve(SKILL_FILENAME);
        if (Files.isRegularFile(rootSkillMd)) {
            PromptSkill skill = loadSingleSkill(rootSkillMd, skillsDir, source);
            return skill != null ? List.of(skill) : Collections.emptyList();
        }

        // 扫描子目录，每个子目录代表一个 Skill
        List<PromptSkill> skills = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (skills.size() >= maxSkillsPerSource) {
                    log.warn("Skill 数量超限，停止加载: dir={}, loaded={}, max={}",
                            skillsDir, skills.size(), maxSkillsPerSource);
                    break;
                }

                if (!Files.isDirectory(entry)) {
                    continue;
                }

                String dirName = entry.getFileName().toString();
                // 跳过隐藏目录和常见非 Skill 目录
                if (dirName.startsWith(".") || "node_modules".equals(dirName)) {
                    continue;
                }

                Path skillMd = entry.resolve(SKILL_FILENAME);
                if (!Files.isRegularFile(skillMd)) {
                    continue;
                }

                PromptSkill skill = loadSingleSkill(skillMd, entry, source);
                if (skill != null) {
                    skills.add(skill);
                }
            }
        } catch (IOException e) {
            log.error("扫描 Skill 目录失败: dir={}", skillsDir, e);
        }

        log.info("PromptSkill 加载完成: dir={}, source={}, count={}", skillsDir, source, skills.size());
        return skills;
    }

    /**
     * 加载单个 SKILL.md 文件并构建 PromptSkill
     *
     * @param skillMdPath SKILL.md 文件路径
     * @param skillDir    Skill 所在目录
     * @param source      来源标识
     * @return PromptSkill 实例，解析失败返回 null
     */
    private PromptSkill loadSingleSkill(Path skillMdPath, Path skillDir, String source) {
        try {
            // 文件大小检查
            long fileSize = Files.size(skillMdPath);
            if (fileSize > maxSkillFileBytes) {
                log.warn("SKILL.md 文件过大，跳过: path={}, size={}, max={}",
                        skillMdPath, fileSize, maxSkillFileBytes);
                return null;
            }

            // symlink 安全检查：确保真实路径在 Skill 目录内
            Path realPath = skillMdPath.toRealPath();
            Path realDir = skillDir.toRealPath();
            if (!realPath.startsWith(realDir)) {
                log.warn("SKILL.md 符号链接逃逸，跳过: path={}, realPath={}", skillMdPath, realPath);
                return null;
            }

            String content = Files.readString(skillMdPath, StandardCharsets.UTF_8);
            Map<String, String> resources = readSiblingResources(skillMdPath, skillDir);
            return parseSkillMd(content, skillDir.getFileName().toString(),
                    skillMdPath.toAbsolutePath().toString(), skillDir.toAbsolutePath().toString(),
                    source, resources);
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败: path={}, error={}", skillMdPath, e.getMessage());
            return null;
        }
    }

    /**
     * 读取技能目录内除 SKILL.md 外的附属 Markdown 文件（L3 资源）。
     * <p>
     * 逐个做大小上限与符号链接逃逸检查，单个附属文件损坏只跳过该文件，
     * 不影响 SKILL.md 与其余附属文件的加载。
     * </p>
     *
     * @param skillMdPath SKILL.md 文件路径（从此集合中排除）
     * @param skillDir    Skill 所在目录
     * @return 文件名 → 内容；无附属文件或读取失败时为空 Map
     */
    private Map<String, String> readSiblingResources(Path skillMdPath, Path skillDir) {
        Map<String, String> resources = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillDir, "*.md")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry) || entry.equals(skillMdPath)
                        || SKILL_FILENAME.equalsIgnoreCase(entry.getFileName().toString())) {
                    continue;
                }
                if (Files.size(entry) > maxSkillFileBytes) {
                    log.warn("附属资源文件过大，跳过: path={}", entry);
                    continue;
                }
                if (!entry.toRealPath().startsWith(skillDir.toRealPath())) {
                    log.warn("附属资源符号链接逃逸，跳过: path={}", entry);
                    continue;
                }
                resources.put(entry.getFileName().toString(), Files.readString(entry, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            log.warn("读取附属资源失败: dir={}, error={}", skillDir, e.getMessage());
        }
        return resources;
    }

    /**
     * 解析 SKILL.md 内容（YAML frontmatter + Markdown 正文）。
     * <p>
     * 包级可见：{@link ClasspathSkillLoader} 复用本方法解析 classpath 来源的技能，
     * 保证"frontmatter 解析只有一条路径"——文件系统与 classpath 两种来源语义一致。
     * </p>
     *
     * @param content      文件内容
     * @param fallbackName frontmatter 缺少 name 字段时的回退技能名（通常为目录名）
     * @param filePath     SKILL.md 路径标识（仅记录，可为文件系统路径或 classpath URI）
     * @param baseDir      技能目录路径标识（仅记录）
     * @param source       来源标识
     * @param resources    L3 附属资源（文件名 → 内容）；无附属文件传空 Map
     * @return PromptSkill 实例，解析失败返回 null
     */
    @SuppressWarnings("unchecked")
    PromptSkill parseSkillMd(String content, String fallbackName, String filePath,
                             String baseDir, String source, Map<String, String> resources) {
        FrontmatterParser.Frontmatter parsed = FrontmatterParser.parse(content);
        if (parsed.yamlFailed()) {
            log.warn("SKILL.md frontmatter 解析失败: path={}", filePath);
        }
        Map<String, Object> frontmatter = parsed.attributes();
        String body = parsed.body();

        // 从 frontmatter 提取标准字段
        String name = extractString(frontmatter, "name");
        if (name == null || name.isBlank()) {
            // 回退到目录名作为 Skill 名称
            name = fallbackName;
        }

        String description = extractString(frontmatter, "description");
        String version = extractString(frontmatter, "version");
        String homepage = extractString(frontmatter, "homepage");

        // 提取 invocation 控制字段（对齐 Anthropic 规范）
        boolean disableModelInvocation = extractBoolean(frontmatter, "disable-model-invocation", false);

        // 提取 allowed-tools（对齐 Anthropic 规范，逗号分隔字符串 → List）
        List<String> allowedTools = extractAllowedTools(frontmatter);

        // 提取 model（对齐 Anthropic 规范）
        String model = extractString(frontmatter, "model");

        // 提取平台元数据（兼容 openclaw 和 orrery 两种 key）
        Map<String, Object> platformMetadata = extractPlatformMetadata(frontmatter);

        // 提取依赖的外部命令列表
        List<String> requiredBins = extractRequiredBins(platformMetadata);

        String promptContent = body != null ? body.strip() : "";

        return PromptSkill.builder()
                .name(name)
                .description(description)
                .promptContent(promptContent)
                .source(source)
                .filePath(filePath)
                .baseDir(baseDir)
                .disableModelInvocation(disableModelInvocation)
                .allowedTools(allowedTools)
                .model(model)
                .frontmatter(frontmatter != null ? frontmatter : Map.of())
                .version(version)
                .homepage(homepage)
                .platformMetadata(platformMetadata)
                .requiredBins(requiredBins)
                .resources(resources != null ? Map.copyOf(resources) : Map.of())
                .build();
    }

    /**
     * 提取平台元数据（兼容 metadata.openclaw 和 metadata.orrery）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPlatformMetadata(Map<String, Object> frontmatter) {
        if (frontmatter == null) {
            return null;
        }
        Object metadata = frontmatter.get("metadata");
        if (!(metadata instanceof Map<?, ?> metaMap)) {
            return null;
        }
        // 优先 orrery，其次 openclaw（兼容两种生态）
        Object orreryMeta = metaMap.get("orrery");
        if (orreryMeta instanceof Map<?, ?>) {
            return (Map<String, Object>) orreryMeta;
        }
        Object openclawMeta = metaMap.get("openclaw");
        if (openclawMeta instanceof Map<?, ?>) {
            return (Map<String, Object>) openclawMeta;
        }
        return null;
    }

    /**
     * 从平台元数据中提取 requires.bins 列表
     */
    private List<String> extractRequiredBins(Map<String, Object> platformMetadata) {
        if (platformMetadata == null) {
            return null;
        }
        Object requires = platformMetadata.get("requires");
        if (!(requires instanceof Map<?, ?> reqMap)) {
            return null;
        }
        Object bins = reqMap.get("bins");
        if (bins instanceof List<?> binList) {
            return binList.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return null;
    }

    /**
     * 提取 allowed-tools 字段（对齐 Anthropic 规范：逗号分隔字符串 → List）
     * <p>
     * 支持格式：{@code "Read,Write,Bash(git:*)"} 或 YAML 列表格式。
     * </p>
     */
    private List<String> extractAllowedTools(Map<String, Object> frontmatter) {
        if (frontmatter == null) {
            return null;
        }
        Object val = frontmatter.get("allowed-tools");
        if (val instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toList();
        }
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .toList();
        }
        return null;
    }

    /**
     * 从 frontmatter 提取字符串值
     */
    private String extractString(Map<String, Object> frontmatter, String key) {
        if (frontmatter == null) {
            return null;
        }
        Object val = frontmatter.get(key);
        return val instanceof String s ? s.trim() : null;
    }

    /**
     * 从 frontmatter 提取布尔值
     */
    private boolean extractBoolean(Map<String, Object> frontmatter, String key, boolean defaultValue) {
        if (frontmatter == null) {
            return defaultValue;
        }
        Object val = frontmatter.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return "true".equalsIgnoreCase(s.trim());
        }
        return defaultValue;
    }
}
