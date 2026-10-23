// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.api;

import java.util.List;

/**
 * 向量索引 SPI（L2/L1 的唯一召回通道）。
 * <p>
 * 标量过滤（tenant/scope/kind/status）在索引表达式内生效，
 * 不做召回后过滤。
 * </p>
 *
 * @author pcy
 */
public interface VectorIndex {

    /**
     * 向量命中。
     *
     * @param memoryId   记忆/情景 ID
     * @param similarity 相似度（0-1，越大越相似）
     */
    record VectorHit(String memoryId, double similarity) {
    }

    /**
     * 写入/更新向量（content 变动时重写）。
     *
     * @param memoryId 记忆 ID
     * @param vector   向量
     * @param item     本体（标量过滤字段来源）
     */
    void upsert(String memoryId, float[] vector, MemoryItem item);

    /**
     * 语义检索（标量过滤在引擎层生效）。
     *
     * @param vector 查询向量
     * @param filter 结构化过滤
     * @param topK   返回上限
     * @return 命中（按相似度降序）
     */
    List<VectorHit> search(float[] vector, MemoryQuery filter, int topK);

    /**
     * 删除向量（擦除/作废联动）。
     *
     * @param memoryId 记忆 ID
     */
    void delete(String memoryId);

    /**
     * 索引是否可用。
     *
     * @return 可用性
     */
    boolean available();
}
