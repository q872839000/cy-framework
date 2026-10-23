// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

/**
 * 画像维度定义（消费方声明"我要记什么"）。
 * <p>
 * key 稳定不变（进存储与提取提示词）；label 与 extractionHint 会被动态
 * 拼装进提取提示词，写清判定指引直接决定提取质量。
 * </p>
 *
 * @param key            维度键（稳定，如 "move_semantics"）
 * @param label          展示名（如 "「移动」指令语义偏好"）
 * @param extractionHint 提取判定指引（进提示词）
 * @param multiValue     多值（标签列表）还是单值
 * @param maxItems       多值时的条目上限（单值忽略）
 * @author pcy
 */
public record ProfileDimension(String key, String label, String extractionHint,
                               boolean multiValue, int maxItems) {

    /**
     * 构造维度定义。
     *
     * @throws IllegalArgumentException key/label 缺失或 maxItems 非法
     */
    public ProfileDimension {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("画像维度 key 不能为空");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("画像维度 label 不能为空: " + key);
        }
        if (multiValue && maxItems < 1) {
            throw new IllegalArgumentException("多值维度 maxItems 须 ≥1: " + key);
        }
    }
}
