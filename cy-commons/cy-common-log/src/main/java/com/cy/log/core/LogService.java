package com.cy.log.core;

/**
 * 日志服务接口
 * <p>
 * 提供编程式日志记录能力，用于在业务代码中手动记录系统日志。
 * 相比注解方式更加灵活，可以记录更详细的业务信息。
 * <p>
 * 使用示例：
 * <pre>
 * {@code
 * // 简单记录
 * logService.log("用户管理", "登录", "用户登录成功");
 *
 * // 记录数据变更
 * logService.logChange("知识库", "修改文档", "Document", "123", oldDoc, newDoc);
 *
 * // 使用构建器
 * logService.builder()
 *     .module("用户管理")
 *     .operation("修改用户")
 *     .targetType("User")
 *     .targetId("123")
 *     .beforeData(oldUser)
 *     .afterData(newUser)
 *     .description("修改用户基本信息")
 *     .record();
 * }
 * </pre>
 *
 * @author pcy
 * @date 2026/01/29
 */
public interface LogService {

    /**
     * 记录系统日志
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param description 操作描述
     */
    void log(String module, String operation, String description);

    /**
     * 记录数据变更日志
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param targetType  操作对象类型
     * @param targetId    操作对象ID
     * @param beforeData  修改前数据
     * @param afterData   修改后数据
     */
    void logChange(String module, String operation, String targetType, String targetId,
                   Object beforeData, Object afterData);

    /**
     * 记录数据变更日志（带描述）
     *
     * @param module      业务模块
     * @param operation   操作类型
     * @param targetType  操作对象类型
     * @param targetId    操作对象ID
     * @param beforeData  修改前数据
     * @param afterData   修改后数据
     * @param description 操作描述
     */
    void logChange(String module, String operation, String targetType, String targetId,
                   Object beforeData, Object afterData, String description);

    /**
     * 获取日志构建器
     * <p>
     * 提供流式API构建复杂的日志记录
     *
     * @return 日志构建器
     */
    LogBuilder builder();

    /**
     * 日志构建器
     * <p>
     * 提供流式API，便于构建复杂的日志记录
     */
    interface LogBuilder {

        /**
         * 设置日志类型
         * <p>
         * 默认 SYSTEM，安全合规类操作应设为 AUDIT
         *
         * @param logType 日志类型
         * @return 构建器
         */
        LogBuilder logType(LogType logType);

        /**
         * 设置业务模块
         *
         * @param module 模块名称
         * @return 构建器
         */
        LogBuilder module(String module);

        /**
         * 设置操作类型
         *
         * @param operation 操作类型
         * @return 构建器
         */
        LogBuilder operation(String operation);

        /**
         * 设置操作描述
         *
         * @param description 描述
         * @return 构建器
         */
        LogBuilder description(String description);

        /**
         * 设置操作对象类型
         *
         * @param targetType 对象类型
         * @return 构建器
         */
        LogBuilder targetType(String targetType);

        /**
         * 设置操作对象ID
         *
         * @param targetId 对象ID
         * @return 构建器
         */
        LogBuilder targetId(String targetId);

        /**
         * 设置修改前数据
         *
         * @param beforeData 修改前数据
         * @return 构建器
         */
        LogBuilder beforeData(Object beforeData);

        /**
         * 设置修改后数据
         *
         * @param afterData 修改后数据
         * @return 构建器
         */
        LogBuilder afterData(Object afterData);

        /**
         * 设置执行是否成功
         *
         * @param success 是否成功
         * @return 构建器
         */
        LogBuilder success(boolean success);

        /**
         * 设置错误消息
         *
         * @param errorMessage 错误消息
         * @return 构建器
         */
        LogBuilder errorMessage(String errorMessage);

        /**
         * 设置变更摘要
         * <p>
         * 简短描述字段级变更，如 "nickname: 张三→李四, status: 1→0"。
         * 如果不手动设置且同时提供了 beforeData 和 afterData，
         * record() 时会自动生成。
         *
         * @param changeSummary 变更摘要
         * @return 构建器
         */
        LogBuilder changeSummary(String changeSummary);

        /**
         * 设置链路追踪 ID
         * <p>
         * 异步线程 / 消息消费等无请求上下文场景下，显式指定归属的 traceId，
         * 使该条记录挂进既有链路树（缺省从当前 InvocationContext 解析）。
         *
         * @param traceId 链路追踪 ID
         * @return 构建器
         */
        LogBuilder traceId(String traceId);

        /**
         * 设置父 span ID
         * <p>
         * 与 {@link #traceId(String)} 配合，把异步产生的记录挂为指定 span 的子节点。
         *
         * @param parentSpanId 父 span ID
         * @return 构建器
         */
        LogBuilder parentSpanId(String parentSpanId);

        /**
         * 设置 span 层深
         * <p>
         * 链路树按 parentSpanId 优先建树，depth 作为保底排序与过滤依据。
         *
         * @param depth 层深（根为 1）
         * @return 构建器
         */
        LogBuilder depth(int depth);

        /**
         * 设置链路 span 身份（类名 + 方法名）
         *
         * @param className  span 类名（如 /engine/PARSING）
         * @param methodName span 方法名
         * @return 构建器
         */
        LogBuilder span(String className, String methodName);

        /**
         * 设置耗时
         *
         * @param durationMs 耗时（毫秒）
         * @return 构建器
         */
        LogBuilder durationMs(long durationMs);

        /**
         * 设置入参数据（对象，经统一载荷处理器脱敏截断）
         *
         * @param inputArgs 入参对象
         * @return 构建器
         */
        LogBuilder inputArgs(Object inputArgs);

        /**
         * 设置返回数据（对象，经统一载荷处理器脱敏截断）
         *
         * @param returnValue 返回对象
         * @return 构建器
         */
        LogBuilder returnValue(Object returnValue);

        /**
         * 记录日志
         * <p>
         * 构建完成后调用此方法提交日志
         */
        void record();
    }
}
