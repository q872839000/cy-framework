package com.cy.log.annotation;

import com.cy.log.access.AccessInterceptor;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 调用链路自定义拦截器注解
 * <p>
 * 标注在方法上，需要同时或在类上标注 {@link AccessLog} 才能生效。
 * 允许在方法执行前后注入自定义逻辑（如时序图生成、监控打点等）。
 * <p>
 * 使用示例：
 * <pre>{@code
 * @AccessLog
 * @Service
 * public class OrderService {
 *
 *     @AccessIntercept(interceptor = MyInterceptor.class,
 *                      module = "订单模块",
 *                      operationDescribe = "创建订单")
 *     public Order createOrder(OrderDTO dto) { ... }
 * }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/09
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface AccessIntercept {

    /**
     * 自定义拦截器实现类
     */
    Class<? extends AccessInterceptor> interceptor();

    /**
     * 模块名称
     * <p>
     * 如：订单模块、用户模块等
     */
    String module() default "";

    /**
     * 操作描述
     * <p>
     * 如：创建订单、修改用户等
     */
    String operationDescribe() default "";
}
