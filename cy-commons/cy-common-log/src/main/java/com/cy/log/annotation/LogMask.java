package com.cy.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 日志脱敏注解
 * <p>
 * 标注在 POJO 字段上，调用链路日志序列化入参/出参时，
 * 该字段的值会被脱敏处理（保留首尾部分字符，中间用 ****** 替代）。
 * <p>
 * 使用示例：
 * <pre>{@code
 * public class User {
 *     private String username;
 *     @LogMask
 *     private String password;
 *     @LogMask
 *     private String idCard;
 * }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/09
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface LogMask {
}
