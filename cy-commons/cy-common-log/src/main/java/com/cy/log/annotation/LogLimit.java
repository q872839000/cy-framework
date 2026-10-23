package com.cy.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 日志字段字符限制注解
 * <p>
 * 标注在 POJO 字段上，调用链路日志序列化时限制该字段的输出长度。
 * 超过长度限制的部分用 ... 省略。适用于 Base64 图片、大文本等场景。
 * <p>
 * 使用示例：
 * <pre>{@code
 * public class Document {
 *     @LogLimit(1024)
 *     private String content;
 *     @LogLimit(256)
 *     private String base64Image;
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
public @interface LogLimit {

    /**
     * 字段最大输出字符数，默认 10240
     */
    int value() default 10240;
}
