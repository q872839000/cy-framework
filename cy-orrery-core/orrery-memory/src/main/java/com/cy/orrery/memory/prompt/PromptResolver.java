// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.prompt;

import com.cy.core.exception.CYException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 提示词解析器（三级覆盖：编程式覆盖 &gt; 配置资源 &gt; 内置默认稿）。
 * <p>
 * 解析结果启动期固化缓存——提示词是稳定资产，运行期不热变；
 * 业务 Agent 覆盖走 {@link #override} 或配置项（如
 * {@code cy.orrery.memory.context.summary-prompt: classpath:prompts/my-summary.md}）。
 * </p>
 *
 * @author pcy
 */
public class PromptResolver {

    /** 资源加载器（支持 classpath:/file: 前缀）。 */
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    /** 配置资源覆盖（键 → 资源路径）。 */
    private final Map<PromptKey, String> configOverrides = new EnumMap<>(PromptKey.class);

    /** 编程式覆盖（键 → 提示词正文，优先级最高）。 */
    private final Map<PromptKey, String> programmaticOverrides = new EnumMap<>(PromptKey.class);

    /** 解析缓存。 */
    private final Map<PromptKey, String> resolved = new EnumMap<>(PromptKey.class);

    /**
     * 配置资源覆盖（自动装配期由配置属性注入）。
     *
     * @param key          提示词键
     * @param resourcePath 资源路径（classpath:/file:）
     */
    public void configureOverride(PromptKey key, String resourcePath) {
        if (key != null && resourcePath != null && !resourcePath.isBlank()) {
            configOverrides.put(key, resourcePath);
            resolved.remove(key);
        }
    }

    /**
     * 编程式覆盖（业务 Bean 直接给正文，最高优先级）。
     *
     * @param key     提示词键
     * @param content 提示词正文
     */
    public void override(PromptKey key, String content) {
        if (key != null && content != null && !content.isBlank()) {
            programmaticOverrides.put(key, content);
            resolved.remove(key);
        }
    }

    /**
     * 解析提示词正文（三级覆盖，结果缓存）。
     *
     * @param key 提示词键
     * @return 提示词正文
     * @throws CYException 配置的资源不可读（50618）
     */
    public String resolve(PromptKey key) {
        return resolved.computeIfAbsent(key, this::doResolve);
    }

    /**
     * 逐级的实际解析。
     *
     * @param key 提示词键
     * @return 提示词正文
     */
    private String doResolve(PromptKey key) {
        String programmatic = programmaticOverrides.get(key);
        if (programmatic != null) {
            return programmatic;
        }
        String configPath = configOverrides.get(key);
        if (configPath != null) {
            return readResource(configPath, key);
        }
        return readResource("classpath:" + key.defaultResource(), key);
    }

    /**
     * 读取资源文本。
     *
     * @param path 资源路径
     * @param key  提示词键（错误上下文）
     * @return 文本
     */
    private String readResource(String path, PromptKey key) {
        Resource resource = resourceLoader.getResource(path);
        try {
            if (!resource.exists()) {
                throw new CYException(50618, path + " (" + key + ")");
            }
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("提示词资源读取失败: " + path, e);
        }
    }
}
