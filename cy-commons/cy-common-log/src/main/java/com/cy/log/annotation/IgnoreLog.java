package com.cy.log.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 忽略日志注解
 * <p>
 * 标记在Controller方法或类上，表示该接口不需要记录日志。
 * 适用于健康检查、心跳等高频调用接口。
 * <p>
 * 使用示例：
 * <pre>
 * {@code
 * // 忽略单个方法
 * @IgnoreLog
 * @GetMapping("/health")
 * public R<String> health() {
 *     return R.ok("ok");
 * }
 *
 * // 忽略整个Controller
 * @IgnoreLog
 * @RestController
 * @RequestMapping("/internal")
 * public class InternalController {
 *     // ...
 * }
 * }
 * </pre>
 *
 * @author pcy
 * @date 2026/01/29
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IgnoreLog {

    /**
     * 忽略原因（可选）
     * <p>
     * 记录忽略日志的原因，便于后续维护
     *
     * @return 忽略原因
     */
    String reason() default "";
}
