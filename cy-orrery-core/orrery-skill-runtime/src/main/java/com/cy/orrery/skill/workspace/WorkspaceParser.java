// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.workspace;

import com.cy.orrery.skill.util.FrontmatterParser;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Workspace 文件解析器
 * <p>
 * 扫描指定目录下的 Workspace 层文件（IDENTITY.md / SOUL.md / USER.md / AGENTS.md / TOOLS.md），
 * 解析 YAML frontmatter 和 Markdown 正文，按优先级排序后返回文档列表。
 * </p>
 * <p>
 * 文件格式：
 * <pre>
 * ---
 * name: My Agent
 * version: 1.0
 * ---
 * Markdown 正文内容...
 * </pre>
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Slf4j
public class WorkspaceParser {

    /**
     * 扫描目录下所有 Workspace 层文件并解析
     *
     * @param workspaceDir Workspace 目录路径
     * @return 按优先级排序的文档列表
     */
    public List<WorkspaceDocument> scanAndParse(Path workspaceDir) {
        if (workspaceDir == null || !Files.isDirectory(workspaceDir)) {
            log.debug("Workspace 目录不存在或无效: {}", workspaceDir);
            return Collections.emptyList();
        }

        List<WorkspaceDocument> documents = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(workspaceDir, "*.md")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                WorkspaceLayer layer = WorkspaceLayer.fromFilename(filename);
                if (layer != null) {
                    try {
                        WorkspaceDocument doc = parseFile(file, layer);
                        if (doc != null) {
                            documents.add(doc);
                        }
                    } catch (IOException | RuntimeException e) {
                        log.warn("解析 Workspace 文件失败: {}, 错误: {}", file, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.error("扫描 Workspace 目录失败: {}", workspaceDir, e);
        }

        // 按优先级排序
        documents.sort(Comparator.comparingInt(d -> d.getLayer().getPriority()));
        log.info("Workspace 扫描完成: dir={}, 文档数={}", workspaceDir, documents.size());
        return documents;
    }

    /**
     * 解析单个 Workspace 文件
     *
     * @param file  文件路径
     * @param layer 所属层
     * @return 解析后的文档，解析失败返回 null
     */
    public WorkspaceDocument parseFile(Path file, WorkspaceLayer layer) throws IOException {
        String content = Files.readString(file);
        Instant lastModified = Files.getLastModifiedTime(file).toInstant();

        FrontmatterParser.Frontmatter parsed = FrontmatterParser.parse(content);
        if (parsed.yamlFailed()) {
            log.warn("YAML frontmatter 解析失败: {}", file);
        }
        Map<String, Object> frontmatter = parsed.attributes() != null
                ? parsed.attributes() : Collections.emptyMap();
        String body = parsed.body();

        return WorkspaceDocument.builder()
                .layer(layer)
                .sourcePath(file.toAbsolutePath().toString())
                .frontmatter(frontmatter != null ? frontmatter : Collections.emptyMap())
                .body(body != null ? body.strip() : "")
                .lastModified(lastModified)
                .build();
    }

    /**
     * 将多个 Workspace 文档合并为系统指令上下文
     * <p>
     * 按优先级顺序拼接各层正文，形成完整的 Agent 系统指令。
     * </p>
     *
     * @param documents 已排序的文档列表
     * @return 合并后的系统指令文本
     */
    public String mergeToSystemPrompt(List<WorkspaceDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (WorkspaceDocument doc : documents) {
            if (doc.getBody() != null && !doc.getBody().isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("# ").append(doc.getLayer().getDescription()).append("\n\n");
                sb.append(doc.getBody());
            }
        }
        return sb.toString();
    }
}
