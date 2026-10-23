// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.tool.builtin;

import com.cy.orrery.protocol.enums.SideEffectLevel;
import com.cy.orrery.skill.tool.ToolCategory;
import com.cy.orrery.skill.tool.ToolExecutionContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * 文件写入工具
 * <p>
 * 参考 OpenClaw 的 {@code write} 工具，支持创建新文件或覆盖已有文件。
 * 自动创建不存在的父目录。支持追加模式。
 * 沙箱模式下严格限制在沙箱根目录内写入。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class FileWriteTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "file_write";
    private static final String TOOL_NAME = "文件写入";
    private static final String TOOL_SUMMARY = "将内容写入指定路径的文件，自动创建父目录，支持覆盖和追加模式。" +
            "写入成功后会返回文件内容，请将内容展示给用户";

    private static final int MAX_CONTENT_RETURN_BYTES = 50_000;

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "文件路径（绝对路径或相对于工作目录的相对路径）"
                },
                "content": {
                  "type": "string",
                  "description": "要写入的文件内容"
                },
                "append": {
                  "type": "boolean",
                  "description": "是否追加模式（默认 false，覆盖写入）"
                },
                "createParents": {
                  "type": "boolean",
                  "description": "是否自动创建不存在的父目录（默认 true）"
                }
              },
              "required": ["path", "content"]
            }
            """;

    /** 构造文件写入工具（文件系统类，副作用 LOW）。 */
    public FileWriteTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.FILESYSTEM, SideEffectLevel.LOW);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String filePath = readStringParam(input, "path", true);
        String content = readStringParam(input, "content", true);
        boolean append = readBoolParam(input, "append", false);
        boolean createParents = readBoolParam(input, "createParents", true);

        Path resolved = resolveAndAssertPath(filePath, context);

        try {
            // 自动创建父目录
            if (createParents) {
                Path parent = resolved.getParent();
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent);
                    log.debug("自动创建父目录: {}", parent);
                }
            }

            // 写入文件
            if (append) {
                Files.writeString(resolved, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(resolved, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            long bytesWritten = content.getBytes(StandardCharsets.UTF_8).length;

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            // 回显调用方传入的路径（沙箱内相对路径），不返回服务器绝对路径：
            // 工具结果会进入 LLM 上下文与用户可见的执行证据，绝对路径既泄漏
            // 部署拓扑，又对客户端（与服务器不同机）无任何意义
            result.put("path", filePath);
            result.put("bytesWritten", bytesWritten);
            result.put("append", append);

            // 对于文本文件，将内容附加到输出中，便于 LLM 直接展示给用户
            if (bytesWritten <= MAX_CONTENT_RETURN_BYTES) {
                result.put("content", content);
            } else {
                result.put("contentTruncated", true);
                result.put("content", content.substring(0, MAX_CONTENT_RETURN_BYTES / 2) + "\n...（内容过长已截断）");
            }

            // 提供沙箱内相对路径，方便下载
            if (context.getSandboxRoot() != null && resolved.startsWith(context.getSandboxRoot())) {
                result.put("relativePath", context.getSandboxRoot().relativize(resolved).toString());
            }

            log.info("文件写入成功: path={}, bytes={}, append={}", filePath, bytesWritten, append);
            return result;
        } catch (IOException e) {
            log.error("文件写入失败: path={}", filePath, e);
            return failureOutput("文件写入失败: " + e.getMessage());
        }
    }
}
