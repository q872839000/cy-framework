package com.cy.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 调用链路日志注解
 * <p>
 * 标注在类或方法上，自动记录方法级调用链路日志。
 * <ul>
 *     <li>标注在类上：该类所有 public 方法均记录调用链路</li>
 *     <li>标注在方法上：仅该方法记录调用链路</li>
 * </ul>
 * 嵌套调用（A 调 B 调 C）会自动使用同一 traceId，栈深度递增，
 * 按调用顺序完整记录每层方法的类名、方法名、入参、出参、耗时。
 * <p>
 * 使用示例：
 * <pre>{@code
 * @AccessLog
 * @Service
 * public class RoleService {
 *     public void updateRole(RoleDTO dto) { ... }
 * }
 *
 * // 仅方法级
 * @AccessLog(reqCharLimit = 2048, retCharLimit = 1024)
 * public List<User> listUsers(String keyword) { ... }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/09
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface AccessLog {

    /**
     * 入参打印字符限制
     * <p>
     * -1 表示不限制，超过限制的部分用 ... 省略
     */
    int reqCharLimit() default -1;

    /**
     * 出参打印字符限制
     * <p>
     * -1 表示不限制，超过限制的部分用 ... 省略
     */
    int retCharLimit() default -1;

    /**
     * 是否记录入参
     * <p>
     * 默认 true，某些大对象参数场景可设为 false
     */
    boolean recordInput() default true;

    /**
     * 是否记录出参
     * <p>
     * 默认 true，某些大返回值场景可设为 false
     */
    boolean recordOutput() default true;
}
