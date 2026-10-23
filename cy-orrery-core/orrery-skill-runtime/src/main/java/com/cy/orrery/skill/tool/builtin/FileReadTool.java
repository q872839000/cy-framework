// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 文件读取工具
 * <p>
 * 参考 OpenClaw 的 {@code read} 工具，支持按行号范围读取文本文件内容。
 * 自动截断超大文件输出，支持偏移量和行数限制分页读取。
 * 沙箱模式下严格限制在沙箱根目录内访问。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class FileReadTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "file_read";
    private static final String TOOL_NAME = "文件读取";
    private static final String TOOL_SUMMARY = "读取指定路径的文件内容，支持按行号范围分页读取";

    private static final int DEFAULT_MAX_LINES = 500;

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "文件路径（绝对路径或相对于工作目录的相对路径）"
                },
                "offset": {
                  "type": "integer",
                  "description": "起始行号（1-indexed），默认从第一行开始"
                },
                "limit": {
                  "type": "integer",
                  "description": "读取行数上限，默认500行"
                }
              },
              "required": ["path"]
            }
            """;

    /** 构造文件读取工具（文件系统类，只读）。 */
    public FileReadTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.FILESYSTEM);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String filePath = readStringParam(input, "path", true);
        int offset = readIntParam(input, "offset", 1);
        int limit = readIntParam(input, "limit", DEFAULT_MAX_LINES);

        if (offset < 1) {
            offset = 1;
        }
        if (limit < 1) {
            limit = DEFAULT_MAX_LINES;
        }

        Path resolved = resolveAndAssertPath(filePath, context);

        if (!Files.exists(resolved)) {
            return failureOutput("文件不存在: " + filePath);
        }
        if (!Files.isRegularFile(resolved)) {
            return failureOutput("路径不是一个普通文件: " + filePath);
        }

        try {
            List<String> allLines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
            int totalLines = allLines.size();

            int startIdx = Math.min(offset - 1, totalLines);
            int endIdx = Math.min(startIdx + limit, totalLines);

            List<String> selectedLines = allLines.subList(startIdx, endIdx);

            // 带行号输出（cat -n 风格）
            int finalOffset = offset;
            String content = IntStream.range(0, selectedLines.size())
                    .mapToObj(i -> String.format("%6d\t%s", finalOffset + i, selectedLines.get(i)))
                    .collect(Collectors.joining("\n"));

            boolean truncated = endIdx < totalLines;

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("content", content);
            result.put("totalLines", totalLines);
            result.put("offset", offset);
            result.put("linesReturned", selectedLines.size());
            result.put("truncated", truncated);

            if (truncated) {
                result.put("nextOffset", endIdx + 1);
                result.put("hint", "文件共 " + totalLines + " 行，当前返回 " + selectedLines.size()
                        + " 行。使用 offset=" + (endIdx + 1) + " 继续读取。");
            }

            log.debug("文件读取成功: path={}, lines={}/{}", filePath, selectedLines.size(), totalLines);
            return result;
        } catch (IOException e) {
            log.error("文件读取失败: path={}", filePath, e);
            return failureOutput("文件读取失败: " + e.getMessage());
        }
    }
}
