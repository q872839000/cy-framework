// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.util.List;

/**
 * 向量化端口（L2/L1 的硬前置；由 cy-common-ai 桥接实现）。
 *
 * @author pcy
 */
public interface EmbeddingPort {

    /**
     * 向量化单条文本。
     *
     * @param text 文本
     * @return 向量
     */
    float[] embed(String text);

    /**
     * 批量向量化。
     *
     * @param texts 文本列表
     * @return 向量列表（与入参等长同序）
     */
    List<float[]> embedAll(List<String> texts);

    /**
     * 向量维度。
     *
     * @return 维度（不可用时 ≤0）
     */
    int dimension();

    /**
     * 是否可用。
     *
     * @return 可用性
     */
    boolean available();
}
