// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.semantic;

/**
 * 语义召回配置（topK + 四维重排权重）。
 *
 * @param topK                召回上限
 * @param similarityWeight    相似度权重
 * @param importanceWeight    重要度权重
 * @param recencyWeight       新鲜度权重
 * @param usageWeight         使用频率权重
 * @param recencyHalfLifeDays 新鲜度半衰期（天）
 * @author pcy
 */
public record RetrievalConfig(int topK, double similarityWeight, double importanceWeight,
                              double recencyWeight, double usageWeight,
                              int recencyHalfLifeDays) {

    /**
     * 默认配置。
     *
     * @return 默认配置
     */
    public static RetrievalConfig defaults() {
        return new RetrievalConfig(8, 0.45, 0.2, 0.2, 0.1, 30);
    }
}
