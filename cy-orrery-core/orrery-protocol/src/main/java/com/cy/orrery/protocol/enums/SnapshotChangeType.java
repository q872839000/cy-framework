// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 快照变更类型枚举
 * <p>
 * 用于增量快照中标记每条 Agent 条目的变更语义，
 * Butler 据此对本地路由视图做精确的增/改/删操作，
 * 避免全量替换带来的性能损耗和状态丢失。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum SnapshotChangeType {

    REGISTER("register", "新注册"),
    UPDATE("update", "描述符或状态更新"),
    DEREGISTER("deregister", "已注销");

    private final String value;
    private final String description;

    SnapshotChangeType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public static SnapshotChangeType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (SnapshotChangeType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
