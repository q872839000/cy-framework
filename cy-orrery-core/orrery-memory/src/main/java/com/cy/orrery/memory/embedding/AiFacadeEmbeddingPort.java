// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.embedding;

import com.cy.ai.AiFacade;
import com.cy.orrery.memory.api.EmbeddingPort;
import java.util.List;

/**
 * AiFacade 向量化端口桥接（复用 cy-common-ai 的角色路由 embedding 能力）。
 *
 * @author pcy
 */
public class AiFacadeEmbeddingPort implements EmbeddingPort {

    /** AI 门面。 */
    private final AiFacade aiFacade;

    /** 向量维度（配置注入，由 embedding 模型决定）。 */
    private final int dimension;

    /**
     * 构造向量化端口桥接。
     *
     * @param aiFacade  AI 门面
     * @param dimension 向量维度（由 embedding 模型决定，配置注入）
     */
    public AiFacadeEmbeddingPort(AiFacade aiFacade, int dimension) {
        this.aiFacade = aiFacade;
        this.dimension = dimension;
    }

    @Override
    public float[] embed(String text) {
        return aiFacade.embedOne(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return aiFacade.embedAll(texts);
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public boolean available() {
        return aiFacade != null && aiFacade.hasEmbeddingProvider();
    }
}
