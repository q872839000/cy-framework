// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.workspace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Workspace 文档模型
 * <p>
 * 表示一个已解析的 Workspace 层文件（如 IDENTITY.md、SOUL.md）。
 * 包含 YAML frontmatter 元数据和 Markdown 正文两部分。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDocument {

    /**
     * 所属层
     */
    private WorkspaceLayer layer;

    /**
     * 源文件路径
     */
    private String sourcePath;

    /**
     * YAML frontmatter 元数据
     */
    private Map<String, Object> frontmatter;

    /**
     * Markdown 正文内容
     */
    private String body;

    /**
     * 文件最后修改时间
     */
    private Instant lastModified;

    /**
     * 从 frontmatter 中获取字符串值
     *
     * @param key 键名
     * @return 字符串值，不存在或非字符串返回 null
     */
    public String getString(String key) {
        if (frontmatter == null) {
            return null;
        }
        Object val = frontmatter.get(key);
        return val instanceof String s ? s : null;
    }

    /**
     * 从 frontmatter 中获取字符串值，带默认值
     *
     * @param key          键名
     * @param defaultValue 默认值
     * @return 字符串值
     */
    public String getString(String key, String defaultValue) {
        String val = getString(key);
        return val != null ? val : defaultValue;
    }
}
