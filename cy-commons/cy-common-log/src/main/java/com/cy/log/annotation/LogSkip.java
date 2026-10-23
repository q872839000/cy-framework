package com.cy.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 日志跳过注解
 * <p>
 * 标注在 POJO 字段上，调用链路日志序列化时完全跳过该字段，不输出到日志中。
 * 适用于无法序列化的对象（如 InputStream、Channel）或完全不需要记录的字段。
 * <p>
 * 使用示例：
 * <pre>{@code
 * public class FileUploadDTO {
 *     private String fileName;
 *     @LogSkip
 *     private InputStream fileStream;
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
public @interface LogSkip {
}
