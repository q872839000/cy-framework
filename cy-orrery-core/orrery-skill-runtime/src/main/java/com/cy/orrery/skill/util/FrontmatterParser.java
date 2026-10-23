// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.skill.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * Markdown frontmatter 解析器（{@code ---} 包围的 YAML 头部 + 正文）。
 * <p>
 * SKILL.md / Workspace 层文件共用同一解析路径：正则定位 YAML 块、SnakeYAML 解析为
 * 属性表。YAML 块损坏时 {@link Frontmatter#yamlFailed()} 为 true、正文仍按切块返回，
 * 由调用方记日志与容错。线程安全约定：{@link Yaml} 实例非线程安全，每次解析新建。
 * </p>
 *
 * @author pcy
 */
public final class FrontmatterParser {

    /** frontmatter 正则：匹配以 {@code ---} 开头和结尾的 YAML 块。 */
    private static final Pattern FRONTMATTER_PATTERN =
            Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?(.*)", Pattern.DOTALL);

    private FrontmatterParser() {
    }

    /**
     * 解析结果。
     *
     * @param attributes frontmatter 属性表（无 frontmatter 块或 YAML 解析失败为 null）
     * @param body       Markdown 正文（frontmatter 块之后的原文，未裁剪空白）
     * @param yamlFailed YAML 块存在但解析失败（调用方应记 warn 日志）
     */
    public record Frontmatter(Map<String, Object> attributes, String body, boolean yamlFailed) {
    }

    /**
     * 解析 Markdown 文本的 frontmatter 与正文。
     *
     * @param content 文件全文
     * @return 解析结果（content 不含 frontmatter 块时 attributes 为 null、body 为原文）
     */
    public static Frontmatter parse(String content) {
        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.matches()) {
            return new Frontmatter(null, content, false);
        }
        String body = matcher.group(2);
        try {
            Object parsed = new Yaml().load(matcher.group(1));
            if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attributes = (Map<String, Object>) map;
                return new Frontmatter(attributes, body, false);
            }
            return new Frontmatter(null, body, false);
        } catch (RuntimeException e) {
            return new Frontmatter(null, body, true);
        }
    }
}
