package com.cy.userfill.annotation;

import java.lang.annotation.*;

/**
 * 自动填充用户名注解
 * <p>
 * 在Controller方法上添加此注解，自动根据返回对象中的createUser和updateUser字段
 * 从Redis缓存中获取用户名填充到createName和updateName字段
 * </p>
 * 
 * @author pcy
 * @date 2026/01/09
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FillUserName {
}
