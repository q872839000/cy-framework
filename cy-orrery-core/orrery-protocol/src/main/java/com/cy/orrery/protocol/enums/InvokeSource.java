// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 调用来源标识
 * <p>
 * 标记 Agent 调用请求的发起方，用于审计追踪和权限判断。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum InvokeSource {

    USER("user", "由终端用户直接发起"),
    BUTLER("butler", "由 Butler 编排器发起"),
    SYSTEM("system", "由平台内部系统发起");

    private final String value;
    private final String description;

    InvokeSource(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static InvokeSource fromValue(String value) {
        if (value == null) return null;
        for (InvokeSource source : values()) {
            if (source.value.equalsIgnoreCase(value.trim())) return source;
        }
        return null;
    }
}
