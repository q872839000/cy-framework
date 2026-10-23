// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 调用模式枚举
 * <p>
 * 定义 Agent 或 Skill 支持的调用方式，
 * Butler 在编排和路由时根据此模式选择合适的调用策略。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Getter
public enum InvocationMode {

    SYNC("sync", "同步调用"),
    ASYNC("async", "异步调用"),
    STREAMING("streaming", "流式调用");

    private final String value;
    private final String description;

    InvocationMode(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 模式值
     * @return 对应枚举，未找到返回 null
     */
    public static InvocationMode fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (InvocationMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        return null;
    }
}
