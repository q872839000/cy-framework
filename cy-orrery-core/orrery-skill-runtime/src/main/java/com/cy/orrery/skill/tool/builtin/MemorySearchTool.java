// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆搜索工具
 * <p>
 * 参考 OpenClaw 的 {@code memory_search} 和 {@code memory_get} 工具，
 * 在指定记忆目录中进行关键词搜索。当前实现为基于文件内容的全文关键词匹配；
 * 后续可替换为向量语义搜索后端（如 Milvus、Elasticsearch）。
 * </p>
 * <p>
 * 记忆文件默认存放在工作目录下的 {@code memory/} 子目录中，
 * 支持 {@code .md} 和 {@code .txt} 格式。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class MemorySearchTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "memory_search";
    private static final String TOOL_NAME = "记忆搜索";
    private static final String TOOL_SUMMARY = "在记忆目录中搜索与查询关键词匹配的内容片段，返回匹配结果及来源";

    private static final String DEFAULT_MEMORY_DIR = "memory";
    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int CONTEXT_LINES = 3;

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "搜索查询关键词"
                },
                "maxResults": {
                  "type": "integer",
                  "description": "最大返回结果数（默认10）"
                },
                "memoryDir": {
                  "type": "string",
                  "description": "记忆目录路径（默认 memory/）"
                }
              },
              "required": ["query"]
            }
            """;

    /** 构造记忆搜索工具（记忆类，只读）。 */
    public MemorySearchTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.MEMORY);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String query = readStringParam(input, "query", true);
        int maxResults = readIntParam(input, "maxResults", DEFAULT_MAX_RESULTS);
        String memoryDir = readStringParam(input, "memoryDir", false);

        if (memoryDir == null) {
            memoryDir = DEFAULT_MEMORY_DIR;
        }

        Path memoryRoot = resolveAndAssertPath(memoryDir, context);

        if (!Files.isDirectory(memoryRoot)) {
            return failureOutput("记忆目录不存在: " + memoryDir);
        }

        List<Map<String, Object>> results = new ArrayList<>();
        String queryLower = query.toLowerCase();

        try {
            Files.walkFileTree(memoryRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= maxResults) {
                        return FileVisitResult.TERMINATE;
                    }

                    String fileName = file.getFileName().toString().toLowerCase();
                    if (!fileName.endsWith(".md") && !fileName.endsWith(".txt")) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        for (int i = 0; i < lines.size() && results.size() < maxResults; i++) {
                            if (lines.get(i).toLowerCase().contains(queryLower)) {
                                // 提取上下文片段
                                int start = Math.max(0, i - CONTEXT_LINES);
                                int end = Math.min(lines.size(), i + CONTEXT_LINES + 1);
                                StringBuilder snippet = new StringBuilder();
                                for (int j = start; j < end; j++) {
                                    snippet.append(lines.get(j)).append('\n');
                                }

                                Map<String, Object> match = new HashMap<>();
                                match.put("path", memoryRoot.relativize(file).toString());
                                match.put("line", i + 1);
                                match.put("snippet", snippet.toString().trim());
                                results.add(match);

                                // 跳过上下文范围内的行，避免重复匹配
                                i = end - 1;
                            }
                        }
                    } catch (IOException e) {
                        log.debug("跳过不可读文件: {}", file);
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("记忆目录遍历失败: dir={}", memoryDir, e);
            return failureOutput("记忆目录遍历失败: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("query", query);
        result.put("results", results);
        result.put("total", results.size());

        log.info("记忆搜索完成: query='{}', hits={}", query, results.size());
        return result;
    }
}
