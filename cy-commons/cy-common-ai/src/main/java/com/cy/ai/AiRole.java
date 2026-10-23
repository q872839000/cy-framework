package com.cy.ai;

import lombok.Getter;

/**
 * AI 模型用途角色枚举
 * <p>
 * 按 <b>业务用途</b> 选择模型，而非按供应商硬编码。
 * 每个角色对应配置文件 {@code cy.ai.roles.<id>} 中映射的模型配置名称。
 * </p>
 * <p>
 * 配置示例：
 * <pre>{@code
 * cy:
 *   ai:
 *     roles:
 *       planning: glm-4-air      # 复杂规划用强模型
 *       fast: glm-4-flash        # 分类/摘要用快模型
 *       embedding: bge-m3        # 向量化专用
 * }</pre>
 * </p>
 * <p>
 * 使用方式：
 * <pre>{@code
 * // 按用途选模型（推荐）
 * aiFacade.chatSync(AiRole.PLANNING, request);
 * aiFacade.chatStream(AiRole.FAST, request);
 *
 * // 若角色未配置，自动降级到 default-model
 * }</pre>
 * </p>
 *
 * @author pcy
 * @date 2026/04/09
 * @see AiFacade
 * @see com.cy.ai.config.AiProperties
 */
@Getter
public enum AiRole {

    /** 默认用途（等价于 default-model） */
    DEFAULT("default"),

    /** 复杂规划/推理（强模型，如 glm-4、deepseek-chat） */
    PLANNING("planning"),

    /** 快速分类/摘要/意图识别（快且便宜，如 glm-4-flash） */
    FAST("fast"),

    /** 向量化/嵌入（embedding 模型） */
    EMBEDDING("embedding"),

    /** 检索结果重排（rerank 模型） */
    RERANK("rerank");

    /**
     * 角色标识
     * <p>
     * 与配置文件 {@code cy.ai.roles.<id>} 中的 key 一一对应。
     * </p>
     */
    private final String id;

    AiRole(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return id;
    }
}
