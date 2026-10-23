package com.cy.log.core;

/**
 * 日志类型枚举
 * <p>
 * 定义系统支持的四种日志类型：
 * <ul>
 *     <li>API - 接口日志，自动捕获Controller层请求</li>
 *     <li>AUDIT - 审计日志，带有审计标识的接口日志</li>
 *     <li>SYSTEM - 系统日志，业务层手动记录的日志</li>
 *     <li>ACCESS - 调用链路日志，方法级调用链路追踪</li>
 * </ul>
 *
 * @author pcy
 * @date 2026/01/29
 */
public enum LogType {

    /**
     * 接口日志
     * <p>
     * 自动捕获所有Controller层的HTTP请求，零代码侵入
     */
    API,

    /**
     * 审计日志
     * <p>
     * 安全合规类操作（登录、用户管理、权限变更等）。来源：
     * <ul>
     *     <li>请求头携带审计标识时，API 日志自动升级</li>
     *     <li>{@code @SysLog(logType = AUDIT)} 注解显式标记</li>
     * </ul>
     */
    AUDIT,

    /**
     * 系统日志
     * <p>
     * 业务操作记录（任务执行、数据变更、配置修改等）。来源：
     * <ul>
     *     <li>{@code @SysLog} 注解（默认 logType = SYSTEM）</li>
     *     <li>{@code LogService.builder()} 编程式 API</li>
     * </ul>
     */
    SYSTEM,

    /**
     * 调用链路日志
     * <p>
     * 方法级调用链路追踪，记录请求从入口到每个 Service/Repository 方法的完整数据流转，
     * 包含类名、方法名、入参、出参、栈深度、耗时。来源：
     * <ul>
     *     <li>{@code @AccessLog} 注解标注在类或方法上</li>
     * </ul>
     */
    ACCESS
}
