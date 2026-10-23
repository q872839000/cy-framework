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
import java.util.HashMap;
import java.util.Map;

/**
 * 文件编辑工具
 * <p>
 * 参考 OpenClaw 的 {@code edit} 工具，执行精确的字符串查找替换操作。
 * 要求 {@code oldString} 在文件中唯一匹配，替换为 {@code newString}。
 * 支持 {@code replaceAll} 模式替换所有匹配项。
 * 沙箱模式下严格限制在沙箱根目录内操作。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
public class FileEditTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "file_edit";
    private static final String TOOL_NAME = "文件编辑";
    private static final String TOOL_SUMMARY = "对文件执行精确的字符串查找替换操作，要求匹配唯一或使用全量替换模式";

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "文件路径"
                },
                "oldString": {
                  "type": "string",
                  "description": "要被替换的原始文本（必须在文件中存在）"
                },
                "newString": {
                  "type": "string",
                  "description": "替换后的新文本"
                },
                "replaceAll": {
                  "type": "boolean",
                  "description": "是否替换所有匹配项（默认 false，仅替换第一个唯一匹配）"
                }
              },
              "required": ["path", "oldString", "newString"]
            }
            """;

    /** 构造文件编辑工具（文件系统类，副作用 LOW）。 */
    public FileEditTool() {
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.FILESYSTEM, SideEffectLevel.LOW);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        String filePath = readStringParam(input, "path", true);
        String oldString = readStringParam(input, "oldString", true);
        String newString = readStringParam(input, "newString", true);
        boolean replaceAll = readBoolParam(input, "replaceAll", false);

        if (oldString.equals(newString)) {
            return failureOutput("oldString 与 newString 相同，无需编辑");
        }

        Path resolved = resolveAndAssertPath(filePath, context);

        if (!Files.exists(resolved)) {
            return failureOutput("文件不存在: " + filePath);
        }

        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            if (!content.contains(oldString)) {
                return failureOutput("未在文件中找到 oldString 匹配");
            }

            String updatedContent;
            int replacements;

            if (replaceAll) {
                // 全量替换
                replacements = countOccurrences(content, oldString);
                updatedContent = content.replace(oldString, newString);
            } else {
                // 唯一匹配检查
                int count = countOccurrences(content, oldString);
                if (count > 1) {
                    return failureOutput("oldString 在文件中匹配 " + count
                            + " 处，非唯一。请提供更精确的上下文或使用 replaceAll=true");
                }
                replacements = 1;
                int idx = content.indexOf(oldString);
                updatedContent = content.substring(0, idx) + newString + content.substring(idx + oldString.length());
            }

            Files.writeString(resolved, updatedContent, StandardCharsets.UTF_8);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            // 回显调用方传入的路径（相对路径），不返回服务器绝对路径（同 FileWriteTool）
            result.put("path", filePath);
            result.put("replacements", replacements);

            // 提供沙箱内相对路径，便于产物收集生成下载链接
            if (context.getSandboxRoot() != null && resolved.startsWith(context.getSandboxRoot())) {
                result.put("relativePath", context.getSandboxRoot().relativize(resolved).toString());
            }

            log.info("文件编辑成功: path={}, replacements={}", filePath, replacements);
            return result;
        } catch (IOException e) {
            log.error("文件编辑失败: path={}", filePath, e);
            return failureOutput("文件编辑失败: " + e.getMessage());
        }
    }

    /**
     * 统计子串在文本中出现的次数
     */
    private int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
