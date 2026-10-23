// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.assets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;

/**
 * 提示词资产解析器（加载点统一入口）。
 * <p>
 * 语义：Provider 优先、classpath 打包副本兜底。入参沿用各加载点现有的 classpath 位置
 * （{@code classpath:prompts/{domain}/{name}.md} 或裸路径），自动映射资产 Key
 * {@code {agentId}/{domain}/{name}}。远端未注册/不可达/未启用时返回 classpath 内容，
 * 行为与迁移前完全等价。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
public class PromptAssetResolver {

    /** 默认读取标签（稳定版）。 */
    public static final String DEFAULT_LABEL = "stable";

    private final PromptAssetProvider provider;
    private final String agentId;
    private final String label;

    /**
     * 构造资产解析器。
     *
     * @param provider 资产提供者（null 表示未启用，纯 classpath）
     * @param agentId  资产 Key 首段
     * @param label    读取标签（默认 stable）
     */
    public PromptAssetResolver(PromptAssetProvider provider, String agentId, String label) {
        this.provider = provider;
        this.agentId = agentId;
        this.label = label == null || label.isBlank() ? DEFAULT_LABEL : label;
    }

    /**
     * 解析模板内容：Provider 优先，classpath 兜底。
     *
     * @param classpathLocation classpath 位置（{@code classpath:prompts/...} 或裸路径）
     * @return 模板内容
     * @throws IllegalStateException 远端与 classpath 均不可得时抛出
     */
    public String resolve(String classpathLocation) {
        String path = stripPrefix(classpathLocation);
        if (provider != null) {
            String key = toKey(path);
            if (key != null) {
                Optional<String> remote = provider.getTemplate(key, label);
                if (remote.isPresent()) {
                    return remote.get();
                }
            }
        }
        return readClasspath(path);
    }

    /**
     * 显式 Key 解析（资产 Key 无法由 prompts/ 约定路径派生时的逃生门；
     * 常规资产一律走单参 {@link #resolve(String)}，Key 自动派生）。
     *
     * @param key               资产 Key（如 {@code orrery-butler/planner/system}）
     * @param classpathLocation classpath 兜底位置
     * @return 模板内容
     */
    public String resolve(String key, String classpathLocation) {
        if (provider != null && key != null && !key.isBlank()) {
            Optional<String> remote = provider.getTemplate(key, label);
            if (remote.isPresent()) {
                return remote.get();
            }
        }
        return readClasspath(stripPrefix(classpathLocation));
    }

    /**
     * classpath 位置 → 资产 Key：{@code prompts/{domain}/{name}.md} → {@code {agentId}/{domain}/{name}}；
     * 非 prompts/ 约定路径返回 null（不进注册表命名空间，直接走 classpath）。
     */
    String toKey(String path) {
        if (path == null || !path.startsWith("prompts/") || !path.endsWith(".md")) {
            return null;
        }
        String body = path.substring("prompts/".length(), path.length() - ".md".length());
        int slash = body.indexOf('/');
        if (slash <= 0 || slash == body.length() - 1) {
            return null;
        }
        return agentId + "/" + body;
    }

    private static String stripPrefix(String location) {
        return location != null && location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
    }

    private static String readClasspath(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("提示词资产远端与 classpath 均不可得: " + path, e);
        }
    }
}
