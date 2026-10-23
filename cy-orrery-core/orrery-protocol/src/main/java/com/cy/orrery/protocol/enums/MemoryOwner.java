// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 记忆所有者
 * <p>
 * 描述某一类记忆由平台（manager/Butler）还是 Agent 自身管理。
 * 影响记忆的读写权限、生命周期和可见性。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum MemoryOwner {

    MANAGER("manager", "平台/Butler 管理"),
    AGENT("agent", "Agent 自身管理");

    private final String value;
    private final String description;

    MemoryOwner(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static MemoryOwner fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (MemoryOwner owner : values()) {
            if (owner.value.equalsIgnoreCase(value.trim())) {
                return owner;
            }
        }
        return null;
    }
}
