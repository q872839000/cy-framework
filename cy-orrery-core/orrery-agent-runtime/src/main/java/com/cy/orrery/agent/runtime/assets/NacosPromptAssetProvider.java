// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.assets;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nacos Prompt Registry 客户端适配器。
 * <p>
 * 只读 stable 语义：结果带进程内缓存（key+label → 模板），首次读取后不再回源；
 * 远端任何异常都静默转 empty，由上层走 classpath 兜底。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
public class NacosPromptAssetProvider implements PromptAssetProvider {

    private static final Logger log = LoggerFactory.getLogger(NacosPromptAssetProvider.class);

    private final AiService aiService;
    private final ConcurrentHashMap<String, Optional<String>> cache = new ConcurrentHashMap<>();

    /**
     * 构造资产提供者。
     *
     * @param aiService Nacos AI 服务客户端
     */
    public NacosPromptAssetProvider(AiService aiService) {
        this.aiService = aiService;
    }

    @Override
    public Optional<String> getTemplate(String key, String label) {
        return cache.computeIfAbsent(key + "@" + label, k -> {
            try {
                Prompt prompt = aiService.getPromptByLabel(key, label);
                if (prompt == null || prompt.getTemplate() == null) {
                    return Optional.empty();
                }
                log.debug("提示词资产命中: key={}, label={}, version={}", key, label, prompt.getVersion());
                return Optional.of(prompt.getTemplate());
            } catch (Exception e) {
                log.warn("提示词资产读取失败（走 classpath 兜底）: key={}, label={}, err={}",
                        key, label, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<String> getTemplateByVersion(String key, String version) {
        return cache.computeIfAbsent(key + "#" + version, k -> {
            try {
                Prompt prompt = aiService.getPromptByVersion(key, version);
                return prompt != null && prompt.getTemplate() != null
                        ? Optional.of(prompt.getTemplate()) : Optional.empty();
            } catch (Exception e) {
                log.warn("提示词资产按版本读取失败: key={}, version={}, err={}", key, version, e.getMessage());
                return Optional.empty();
            }
        });
    }
}
