package com.cy.log.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 日志数据库实体
 * <p>
 * 对应数据库中的日志表，支持动态表名（分表）
 *
 * @author pcy
 * @date 2026/01/29
 */
@Data
@Table("sys_log")
public class LogEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 基础信息 ====================

    /**
     * 日志ID
     */
    @Id(keyType = KeyType.None)
    private Long id;

    /**
     * 链路追踪ID
     */
    private String traceId;

    /**
     * 当前日志所属的 Span ID
     */
    private String spanId;

    /**
     * 请求来源
     */
    private String source;

    /**
     * 日志类型：API/AUDIT/SYSTEM/ACCESS
     */
    private String logType;

    /**
     * 服务名称
     */
    private String serviceName;

    /**
     * 服务器IP
     */
    private String serverIp;

    /**
     * 父Span ID（调用方的spanId）
     */
    private String parentSpanId;

    // ==================== 请求信息 ====================

    /**
     * 请求方法
     */
    private String requestMethod;

    /**
     * 请求URI
     */
    private String requestUri;

    /**
     * 请求参数（JSON）
     */
    private String requestParams;

    /**
     * 请求体（JSON）
     */
    private String requestBody;

    /**
     * 请求头（JSON）
     */
    private String requestHeaders;

    /**
     * 客户端IP
     */
    private String clientIp;

    /**
     * 用户代理
     */
    private String userAgent;

    // ==================== 响应信息 ====================

    /**
     * 响应状态码
     */
    private Integer responseStatus;

    /**
     * 响应体（JSON）
     */
    private String responseBody;

    /**
     * 请求耗时（毫秒）
     */
    private Long duration;

    // ==================== 用户信息 ====================

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户昵称
     */
    private String nickname;

    // ==================== 业务信息 ====================

    /**
     * 业务模块
     */
    private String module;

    /**
     * 操作类型
     */
    private String operation;

    /**
     * 操作对象类型
     */
    private String targetType;

    /**
     * 操作对象ID
     */
    private String targetId;

    /**
     * 修改前数据（JSON）
     */
    private String beforeData;

    /**
     * 修改后数据（JSON）
     */
    private String afterData;

    /**
     * 操作描述
     */
    private String description;

    /**
     * 变更摘要
     */
    private String changeSummary;

    // ==================== 调用链路信息 ====================

    /**
     * 调用类名
     */
    private String className;

    /**
     * 调用方法名
     */
    private String methodName;

    /**
     * 调用栈深度
     */
    private Integer depth;

    /**
     * 实际执行耗时（毫秒）
     */
    private Long actualDuration;

    /**
     * 方法入参（JSON）
     */
    private String inputArgs;

    /**
     * 方法返回值（JSON）
     */
    private String returnValue;

    // ==================== 异常信息 ====================

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 错误消息
     */
    private String errorMessage;

    /**
     * 异常堆栈
     */
    private String stackTrace;

    // ==================== 时间信息 ====================

    /**
     * 创建时间（毫秒时间戳）
     */
    private Long createTime;

    /**
     * 分表标识
     */
    @Column(ignore = true)
    private String tablePartition;
}
