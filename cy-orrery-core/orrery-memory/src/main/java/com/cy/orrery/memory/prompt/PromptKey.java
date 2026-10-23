// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.prompt;

/**
 * 内置提示词键（对应 {@code prompts/<resource>} 默认稿）。
 *
 * @author pcy
 */
public enum PromptKey {

    /** L0 滚动摘要合并。 */
    CONTEXT_SUMMARY("prompts/context-summary.md"),

    /** L2 事实提取。 */
    FACT_EXTRACT("prompts/fact-extract.md"),

    /** L2 写入决策。 */
    WRITE_DECISION("prompts/write-decision.md"),

    /** 画像提取骨架（维度段永远动态拼装）。 */
    PROFILE_EXTRACT("prompts/profile-extract.md"),

    /** L1 情景卡片沉淀。 */
    EPISODE_SETTLE("prompts/episode-settle.md");

    /** 默认稿的 classpath 资源路径。 */
    private final String defaultResource;

    PromptKey(String defaultResource) {
        this.defaultResource = defaultResource;
    }

    /**
     * 默认稿资源路径。
     *
     * @return classpath 资源路径
     */
    public String defaultResource() {
        return defaultResource;
    }
}
