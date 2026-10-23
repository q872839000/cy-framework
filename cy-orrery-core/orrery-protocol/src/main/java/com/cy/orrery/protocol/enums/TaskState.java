// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 任务生命周期状态枚举（对齐 A2A 协议 TaskState）
 * <p>
 * 定义 Agent 处理任务的生命周期状态，Butler 在编排时追踪任务进度。
 * 与 Google A2A 协议的 TaskState 保持语义一致。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Getter
public enum TaskState {

    SUBMITTED("submitted", "已提交，等待处理"),
    WORKING("working", "处理中"),
    INPUT_REQUIRED("input-required", "需要用户补充输入"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    CANCELED("canceled", "已取消"),
    REJECTED("rejected", "被拒绝"),
    AUTH_REQUIRED("auth-required", "需要鉴权");

    private final String value;
    private final String description;

    TaskState(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 判断是否为终态
     *
     * @return 是否为终态
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 状态值
     * @return 对应枚举，未找到返回 null
     */
    public static TaskState fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TaskState state : values()) {
            if (state.value.equalsIgnoreCase(value.trim())) {
                return state;
            }
        }
        return null;
    }
}
