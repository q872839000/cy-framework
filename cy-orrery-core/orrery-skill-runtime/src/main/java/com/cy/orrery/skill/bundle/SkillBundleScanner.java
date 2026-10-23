// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.bundle;

import com.cy.orrery.skill.util.FrontmatterParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill Bundle 目录扫描器
 * <p>
 * 扫描指定目录（如 {@code ~/.orrery/skills/} 或 Agent 本地 {@code skills/}）下的子目录，
 * 识别包含 {@code SKILL.md} 的 Skill Bundle，解析元数据并返回 {@link SkillBundle} 列表。
 * </p>
 * <p>
 * Bundle 目录结构：
 * <pre>
 * skills/
 *   my-skill/
 *     SKILL.md          ← 必须存在（含 YAML frontmatter + 描述正文）
 *     instructions.md   ← 可选（执行指令）
 *     schema.json       ← 可选（输入/输出 JSON Schema）
 *     examples/         ← 可选（示例目录）
 *       example1.md
 * </pre>
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Slf4j
public class SkillBundleScanner {

    private static final String SKILL_MD = "SKILL.md";
    private static final String INSTRUCTIONS_MD = "instructions.md";
    private static final String SCHEMA_JSON = "schema.json";
    private static final String EXAMPLES_DIR = "examples";

    /** 默认版本号（frontmatter 缺 version 时的兜底）。 */
    private static final String DEFAULT_VERSION = "1.0.0";

    private final ObjectMapper objectMapper;

    /**
     * 构造扫描器（自建 JSON 编解码器）。
     */
    public SkillBundleScanner() {
        this(new ObjectMapper());
    }

    /**
     * 构造扫描器。
     *
     * @param objectMapper JSON 编解码器（schema.json 解析）
     */
    public SkillBundleScanner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 扫描目录下的所有 Skill Bundle
     *
     * @param skillsDir 技能目录（包含多个子 bundle 目录）
     * @return 扫描到的 SkillBundle 列表
     */
    public List<SkillBundle> scan(Path skillsDir) {
        if (skillsDir == null || !Files.isDirectory(skillsDir)) {
            log.debug("技能目录不存在: {}", skillsDir);
            return Collections.emptyList();
        }

        List<SkillBundle> bundles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    Path skillMd = entry.resolve(SKILL_MD);
                    if (Files.isRegularFile(skillMd)) {
                        try {
                            SkillBundle bundle = parseBundle(entry);
                            if (bundle != null) {
                                bundles.add(bundle);
                            }
                        } catch (IOException | RuntimeException e) {
                            log.warn("解析 Skill Bundle 失败: {}, 错误: {}", entry, e.getMessage());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("扫描技能目录失败: {}", skillsDir, e);
        }

        log.info("Skill Bundle 扫描完成: dir={}, 发现 {} 个", skillsDir, bundles.size());
        return bundles;
    }

    /**
     * 解析单个 Skill Bundle 目录
     *
     * @param bundleDir Bundle 目录路径
     * @return 解析后的 SkillBundle
     * @throws IOException SKILL.md 读取失败
     */
    @SuppressWarnings("unchecked")
    public SkillBundle parseBundle(Path bundleDir) throws IOException {
        Path skillMd = bundleDir.resolve(SKILL_MD);
        String content = Files.readString(skillMd);

        // 解析 SKILL.md 的 frontmatter + 正文
        FrontmatterParser.Frontmatter parsed = FrontmatterParser.parse(content);
        if (parsed.yamlFailed()) {
            log.warn("SKILL.md frontmatter 解析失败: {}", skillMd);
        }
        Map<String, Object> frontmatter = parsed.attributes() != null
                ? parsed.attributes() : Collections.emptyMap();
        String description = parsed.body().strip();

        String dirName = bundleDir.getFileName().toString();

        // 读取可选文件
        String instructions = readOptionalFile(bundleDir.resolve(INSTRUCTIONS_MD));
        Map<String, Object> inputSchema = null;
        Map<String, Object> outputSchema = null;
        Path schemaFile = bundleDir.resolve(SCHEMA_JSON);
        if (Files.isRegularFile(schemaFile)) {
            try {
                Map<String, Object> schemaMap = objectMapper.readValue(
                        schemaFile.toFile(), new TypeReference<>() {});
                inputSchema = schemaMap.containsKey("input")
                        ? (Map<String, Object>) schemaMap.get("input") : null;
                outputSchema = schemaMap.containsKey("output")
                        ? (Map<String, Object>) schemaMap.get("output") : null;
            } catch (IOException | RuntimeException e) {
                log.warn("schema.json 解析失败: {}, 错误: {}", schemaFile, e.getMessage());
            }
        }

        // 读取 examples
        List<String> examples = readExamples(bundleDir.resolve(EXAMPLES_DIR));

        // 提取 tags
        List<String> tags = null;
        Object rawTags = frontmatter.get("tags");
        if (rawTags instanceof List<?> tagList) {
            tags = tagList.stream().map(Object::toString).toList();
        }

        return SkillBundle.builder()
                .skillId(getStringOrDefault(frontmatter, "id", dirName))
                .name(getStringOrDefault(frontmatter, "name", dirName))
                .version(getStringOrDefault(frontmatter, "version", DEFAULT_VERSION))
                .description(description)
                .instructions(instructions)
                .tags(tags)
                .author(getStringOrDefault(frontmatter, "author", null))
                .license(getStringOrDefault(frontmatter, "license", null))
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .frontmatter(frontmatter)
                .bundlePath(bundleDir.toAbsolutePath().toString())
                .examples(examples)
                .build();
    }

    /** 读取可选文本文件（缺失或读取失败返回 null）。 */
    private String readOptionalFile(Path file) {
        if (Files.isRegularFile(file)) {
            try {
                return Files.readString(file).strip();
            } catch (IOException e) {
                log.warn("读取可选文件失败: {}, 错误: {}", file, e.getMessage());
            }
        }
        return null;
    }

    /** 读取 examples 目录下的 Markdown 示例（无目录或无有效示例返回 null）。 */
    private List<String> readExamples(Path examplesDir) {
        if (!Files.isDirectory(examplesDir)) {
            return null;
        }
        List<String> examples = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(examplesDir, "*.md")) {
            for (Path file : stream) {
                String content = Files.readString(file).strip();
                if (!content.isEmpty()) {
                    examples.add(content);
                }
            }
        } catch (IOException e) {
            log.warn("读取示例目录失败: {}, 错误: {}", examplesDir, e.getMessage());
        }
        return examples.isEmpty() ? null : examples;
    }

    /** 取字符串字段（缺失或非字符串/空白时落默认值）。 */
    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val instanceof String s && !s.isBlank()) {
            return s;
        }
        return defaultValue;
    }
}
