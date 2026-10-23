// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.assets;

import java.util.Optional;

/**
 * 提示词资产提供者（Nacos Prompt Registry 薄适配）。
 * <p>
 * 只负责按 Key + 标签取模板；变量渲染仍走各消费方既有机制，本接口不自建模板引擎。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
public interface PromptAssetProvider {

    /**
     * 按 Key 与标签取模板。
     *
     * @param key   资产 Key（约定 {@code {agentId}/{domain}/{name}}）
     * @param label 标签（stable / canary / latest）
     * @return 模板内容；不存在或远端不可达返回 empty
     */
    Optional<String> getTemplate(String key, String label);

    /**
     * 按 Key 与版本取模板。
     *
     * @param key     资产 Key
     * @param version 版本号
     * @return 模板内容；不存在返回 empty
     */
    Optional<String> getTemplateByVersion(String key, String version);
}
