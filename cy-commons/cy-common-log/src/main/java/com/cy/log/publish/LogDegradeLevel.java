package com.cy.log.publish;

/**
 * 日志发布降级等级。
 *
 * @author pcy
 * @date 2026/04/14
 */
public enum LogDegradeLevel {

    /**
     * 正常状态。
     */
    NORMAL,

    /**
     * 压力状态。
     */
    PRESSURE,

    /**
     * 紧急状态。
     */
    EMERGENCY
}
