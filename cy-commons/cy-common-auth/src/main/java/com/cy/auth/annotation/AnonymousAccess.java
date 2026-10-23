package com.cy.auth.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口或控制器为匿名可访问（免鉴权）。
 * <p>
 * 标注在方法上：该方法对应的路径免鉴权。<br>
 * 标注在类上：该类所有方法对应的路径均免鉴权。
 * </p>
 * <p>
 * 仅在引入 {@code cy-common-auth} 模块后生效；
 * 未引入 auth 时所有路径默认无鉴权，此注解仅作文档性标记。
 * </p>
 *
 * @author pcy
 * @date 2026/03/25
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnonymousAccess {
}
