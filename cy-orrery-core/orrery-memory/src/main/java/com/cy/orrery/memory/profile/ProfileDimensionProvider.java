// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.memory.profile;

import java.util.List;

/**
 * 画像维度供给 SPI（消费方实现并注册为 Spring Bean，声明本服务要记的维度）。
 * <p>
 * 组件只提供画像的生成/存储/召回能力；记哪些维度、什么标签完全由
 * 使用方决定。多个 Provider 的维度列表合并装配，key 重复启动拒绝。
 * </p>
 *
 * @author pcy
 */
public interface ProfileDimensionProvider {

    /**
     * 本服务要记录的画像维度。
     *
     * @return 维度定义列表（空列表 = 不记画像）
     */
    List<ProfileDimension> dimensions();
}
