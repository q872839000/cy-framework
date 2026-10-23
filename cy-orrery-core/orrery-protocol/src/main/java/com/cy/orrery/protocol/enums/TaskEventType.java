// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.enums;

import lombok.Getter;

/**
 * 任务事件类型枚举
 * <p>
 * 用于标识 TaskEvent 中发生的具体事件类型，
 * 覆盖任务创建、步骤执行、确认等待、任务终态等全部生命周期节点。
 * </p>
 *
 * @author pcy
 * @date 2026/03/18
 */
@Getter
public enum TaskEventType {

    TASK_CREATED("task_created", "任务已创建"),
    STEP_STARTED("step_started", "步骤开始执行"),
    STEP_COMPLETED("step_completed", "步骤执行完成"),
    STEP_FAILED("step_failed", "步骤执行失败"),
    WAITING_CONFIRMATION("waiting_confirmation", "等待用户确认"),
    TASK_COMPLETED("task_completed", "任务已完成"),
    TASK_FAILED("task_failed", "任务已失败"),
    TASK_CANCELED("task_canceled", "任务已取消");

    private final String value;
    private final String description;

    TaskEventType(String value, String description) {
        this.value = value;
        this.description = description;
    }

    /**
     * 根据字符串值查找枚举（忽略大小写）
     *
     * @param value 事件类型值
     * @return 对应枚举，未找到返回 null
     */
    public static TaskEventType fromValue(String value) {
        if (value == null) {
            return null;
        }
        for (TaskEventType type : values()) {
            if (type.value.equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return null;
    }
}
