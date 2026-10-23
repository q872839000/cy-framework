package com.cy.log.annotation;

import com.cy.log.core.LogType;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 系统日志注解
 * <p>
 * 标记在Service层方法上，用于记录业务操作日志。
 * 支持记录数据变更前后的内容，便于审计追溯。
 * <p>
 * 使用示例：
 * <pre>
 * {@code
 * @SysLog(module = "用户管理", operation = "修改用户信息")
 * public void updateUser(User user) {
 *     // 业务逻辑
 * }
 *
 * @SysLog(module = "知识库", operation = "删除文档",
 *         targetIdEl = "#id", recordBefore = true)
 * public void deleteDocument(Long id) {
 *     // 业务逻辑
 * }
 * }
 * </pre>
 *
 * @author pcy
 * @date 2026/01/29
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SysLog {

    /**
     * 业务模块名称
     * <p>
     * 如：用户管理、知识库、文档管理等
     *
     * @return 模块名称
     */
    String module() default "";

    /**
     * 操作类型
     * <p>
     * 如：新增、修改、删除、导入、导出等
     *
     * @return 操作类型
     */
    String operation();

    /**
     * 操作描述
     * <p>
     * 对本次操作的详细描述，支持SpEL表达式
     *
     * @return 操作描述
     */
    String description() default "";

    /**
     * 操作对象类型
     * <p>
     * 如：User、Document等实体类名
     *
     * @return 对象类型
     */
    String targetType() default "";

    /**
     * 目标ID的SpEL表达式
     * <p>
     * 用于从方法参数中获取操作对象的ID
     * 如：#user.id、#id、#dto.documentId
     *
     * @return SpEL表达式
     */
    String targetIdEl() default "";

    /**
     * 是否记录修改前的数据
     * <p>
     * 设为true时，会在方法执行前通过 {@link #beforeDataEl()} 表达式查询并记录数据的原始状态。
     * 若未指定 beforeDataEl，则将方法入参序列化为修改前数据快照。
     *
     * @return 是否记录修改前数据
     */
    boolean recordBefore() default false;

    /**
     * 修改前数据的SpEL表达式
     * <p>
     * 当 {@link #recordBefore()} 为true时，通过此SpEL表达式获取修改前的数据。
     * 表达式在方法执行前求值，可引用方法参数和Spring Bean。
     * <p>
     * 使用示例：
     * <pre>
     * {@code
     * // 引用 Spring Bean 方法查询原始数据
     * @SysLog(module = "用户管理", operation = "修改用户",
     *         recordBefore = true, beforeDataEl = "@userService.getById(#dto.id)")
     *
     * // 直接引用方法参数
     * @SysLog(module = "知识库", operation = "更新文档",
     *         recordBefore = true, beforeDataEl = "#oldDoc")
     * }
     * </pre>
     *
     * @return SpEL表达式，为空时回退到序列化方法入参
     */
    String beforeDataEl() default "";

    /**
     * 是否记录修改后的数据
     * <p>
     * 设为true时，会记录方法的返回值或最终数据状态
     *
     * @return 是否记录修改后数据
     */
    boolean recordAfter() default false;

    /**
     * 日志类型
     * <p>
     * 默认 SYSTEM（系统日志），安全合规类操作应设为 AUDIT（审计日志）
     *
     * @return 日志类型
     */
    LogType logType() default LogType.SYSTEM;
}
