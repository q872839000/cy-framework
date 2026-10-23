// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.assets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.Resource;

/**
 * 提示词模板渲染工具（全生态统一标准）。
 * <p>
 * 把模板文件读取、占位符替换、分片解析这些机械动作集中到一处，
 * 供各 Agent / 服务的提示词持有者共用，避免各处重复实现读文件 / 替换逻辑：
 * </p>
 * <ul>
 *     <li>{@link #read(Resource)}：读取整份模板，逐行以 {@code \n} 连接（不含结尾换行）。</li>
 *     <li>{@link #render(String, Map)}：把 {@code {{key}}} 占位符替换为给定取值。</li>
 *     <li>{@link #readFragments(Resource)}：解析以 {@code [[name]]} 行分段的多片模板，
 *         用于"按条件拼接若干句子"的场景。</li>
 * </ul>
 * 模板资产一律走 {@code prompts/{domain}/{name}.md} 目录约定，加载经
 * {@link PromptAssetResolver}（Nacos Prompt Registry 优先、classpath 兜底）。
 *
 * @author pcy
 * @since 2026/09/03
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /**
     * 读取模板文件全文，逐行用 {@code \n} 连接（结尾不补换行）。
     *
     * @param resource 模板资源
     * @return 模板正文
     * @throws IllegalStateException 资源缺失或读取失败
     */
    public static String read(Resource resource) {
        if (resource == null || !resource.exists()) {
            throw new IllegalStateException("提示词模板缺失: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("读取提示词模板失败: " + resource, ex);
        }
    }

    /**
     * 渲染占位符模板。占位符格式为 {@code {{key}}}，按字面量替换（不走正则）。
     *
     * @param template 模板正文
     * @param values   占位符取值（{@code null} 取值按空串处理）
     * @return 渲染后的文本
     */
    public static String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return rendered;
    }

    /**
     * 解析分片模板：以单独成行的 {@code [[name]]} 作为分段标记，返回"片段名 → 片段正文"。
     * <p>
     * 片段正文为该标记行之后、下一标记行（或文件末尾）之前的内容，逐行以 {@code \n} 连接，
     * 并裁掉首尾的纯空行（便于在模板里用空行分隔各片段以提升可读性）；行内的前导空格视为正文保留。
     * </p>
     *
     * @param resource 分片模板资源
     * @return 有序的片段表（保持文件中出现顺序）
     */
    public static Map<String, String> readFragments(Resource resource) {
        return readFragments(read(resource));
    }

    /**
     * 解析分片模板正文（资产通道内容直进时用本入口）。分段规则同 {@link #readFragments(Resource)}。
     *
     * @param content 分片模板正文
     * @return 有序的片段表（保持文件中出现顺序）
     */
    public static Map<String, String> readFragments(String content) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        String current = null;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[[") && trimmed.endsWith("]]") && trimmed.length() > 4) {
                current = trimmed.substring(2, trimmed.length() - 2).strip();
                sections.computeIfAbsent(current, k -> new ArrayList<>());
            } else if (current != null) {
                sections.get(current).add(line);
            }
        }
        Map<String, String> fragments = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : sections.entrySet()) {
            fragments.put(entry.getKey(), joinTrimmingBlankEdges(entry.getValue()));
        }
        return fragments;
    }

    /** 去掉首尾纯空行后，用 {@code \n} 连接片段内容行。 */
    private static String joinTrimmingBlankEdges(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isEmpty()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isEmpty()) {
            end--;
        }
        return String.join("\n", lines.subList(start, end));
    }

    /** 空值安全转换：{@code null} → 空串。 */
    public static String safe(Object value) {
        return Objects.toString(value, "");
    }
}
