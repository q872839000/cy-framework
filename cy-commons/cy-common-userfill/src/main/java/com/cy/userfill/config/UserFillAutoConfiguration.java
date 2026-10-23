package com.cy.userfill.config;

import com.cy.userfill.aspect.UserNameFillAspect;
import com.cy.redis.utils.RedisUtils;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * 用户名自动填充自动配置类
 * 
 * @author pcy
 * @date 2026/01/09
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnClass({RedisUtils.class})
public class UserFillAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UserNameFillAspect userNameFillAspect() {
        return new UserNameFillAspect();
    }
}
