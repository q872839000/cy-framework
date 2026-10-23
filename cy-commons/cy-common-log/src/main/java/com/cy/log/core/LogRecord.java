package com.cy.log.core;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 日志记录实体
 * <p>
 * 统一的日志数据结构，用于MQ传输和持久化存储。
 * 包含请求信息、响应信息、用户信息、业务信息、异常信息等完整的日志字段。
 *
 * @author pcy
 * @date 2026/04/06
 */
@Data
public class LogRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ==================== 基础信息 ====================

    /**
     * 日志ID（雪花算法生成）
     */
    private Long id;

    /**
     * 链路追踪ID
     * <p>
     * 用于关联分布式系统中的请求链路，同一请求链路中的所有日志共享相同的traceId
     */
    private String traceId;

    /**
     * 当前日志所属的 Span ID
     * <p>
     * 同一链路下每个 span 唯一，用于区分同一 trace 中的不同调用段。
     */
    private String spanId;

    /**
     * 请求来源
     * <p>
     * 标识操作来源：external（外部直接访问）、gateway（网关转发）、internal（服务间调用）、system（系统自发）
     */
    private String source;

    /**
     * 日志类型
     *
     * @see LogType
     */
    private LogType logType;

    /**
     * 服务名称
     * <p>
     * 来源于 spring.application.name 配置
     */
    private String serviceName;

    /**
     * 服务器IP地址
     */
    private String serverIp;

    /**
     * 父Span ID
     * <p>
     * 指向调用方的 spanId，用于构建调用链路树。
     * 根节点时为 {@code null}。
     */
    private String parentSpanId;

    // ==================== 请求信息 ====================

    /**
     * HTTP请求方法
     * <p>
     * GET、POST、PUT、DELETE等
     */
    private String requestMethod;

    /**
     * 请求URI
     * <p>
     * 不包含查询参数，如：/api/user/update
     */
    private String requestUri;

    /**
     * 请求查询参数（JSON格式）
     * <p>
     * URL中的查询参数，如：{"page":"1","size":"10"}
     */
    private String requestParams;

    /**
     * 请求体（JSON格式）
     * <p>
     * POST、PUT等请求的请求体内容，敏感字段会被脱敏
     */
    private String requestBody;

    /**
     * 关键请求头（JSON格式）
     * <p>
     * 仅记录关键的请求头信息，如Content-Type、User-Agent等
     */
    private String requestHeaders;

    /**
     * 客户端IP地址
     * <p>
     * 支持从X-Forwarded-For、X-Real-IP等代理头中获取真实IP
     */
    private String clientIp;

    /**
     * 用户代理
     * <p>
     * 客户端浏览器或设备信息
     */
    private String userAgent;

    // ==================== 响应信息 ====================

    /**
     * HTTP响应状态码
     * <p>
     * 如：200、400、500等
     */
    private Integer responseStatus;

    /**
     * 响应体（JSON格式）
     * <p>
     * 接口响应内容，可通过配置控制是否记录
     */
    private String responseBody;

    /**
     * 请求耗时（毫秒）
     */
    private Long duration;

    // ==================== 用户信息 ====================

    /**
     * 用户ID
     * <p>
     * 从 InvocationContext 中获取
     */
    private Long userId;

    /**
     * 用户名
     * <p>
     * 从 InvocationContext 中获取
     */
    private String username;

    /**
     * 用户昵称
     * <p>
     * 从 InvocationContext 中获取
     */
    private String nickname;

    // ==================== 业务信息（系统日志专用） ====================

    /**
     * 业务模块
     * <p>
     * 如：用户管理、知识库、文档管理等
     */
    private String module;

    /**
     * 操作类型
     * <p>
     * 如：新增、修改、删除、查询等
     */
    private String operation;

    /**
     * 操作对象类型
     * <p>
     * 如：User、Document、Category等实体类名
     */
    private String targetType;

    /**
     * 操作对象ID
     * <p>
     * 被操作的数据主键ID
     */
    private String targetId;

    /**
     * 修改前数据（JSON格式）
     * <p>
     * 记录数据修改前的完整内容，用于审计追溯
     */
    private String beforeData;

    /**
     * 修改后数据（JSON格式）
     * <p>
     * 记录数据修改后的完整内容，用于审计追溯
     */
    private String afterData;

    /**
     * 操作描述
     * <p>
     * 对本次操作的文字描述
     */
    private String description;

    /**
     * 变更摘要
     * <p>
     * 简短的变更对比描述，如 "nickname: 张三→李四, status: 1→0"
     */
    private String changeSummary;

    // ==================== 调用链路信息（ACCESS日志专用） ====================

    /**
     * 调用类名
     * <p>
     * 被标注 {@code @AccessLog} 的方法所在类的简单名称
     */
    private String className;

    /**
     * 调用方法名
     * <p>
     * 被标注 {@code @AccessLog} 的方法名称
     */
    private String methodName;

    /**
     * 调用栈深度
     * <p>
     * 同一请求中的方法调用层级，入口为 1，嵌套调用递增
     */
    private Integer depth;

    /**
     * 实际执行耗时（毫秒）
     * <p>
     * 当前方法的实际耗时 = 总耗时 - 子方法耗时之和，用于定位性能瓶颈
     */
    private Long actualDuration;

    /**
     * 方法入参（JSON格式）
     * <p>
     * 方法调用时的参数值，敏感字段已脱敏，支持字符截断
     */
    private String inputArgs;

    /**
     * 方法返回值（JSON格式）
     * <p>
     * 方法执行完毕后的返回值，敏感字段已脱敏，支持字符截断
     */
    private String returnValue;

    // ==================== 异常信息 ====================

    /**
     * 是否执行成功
     */
    private Boolean success;

    /**
     * 错误消息
     * <p>
     * 当请求失败时记录的错误信息
     */
    private String errorMessage;

    /**
     * 异常堆栈
     * <p>
     * 当发生异常时记录的完整堆栈信息
     */
    private String stackTrace;

    // ==================== 时间信息 ====================

    /**
     * 创建时间（毫秒时间戳）
     */
    private Long createTime;

    /**
     * 分表标识
     * <p>
     * 格式：yyyyMM，如：202601
     */
    private String tablePartition;
}
