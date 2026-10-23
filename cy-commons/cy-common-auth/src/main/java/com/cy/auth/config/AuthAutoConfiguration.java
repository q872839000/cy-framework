package com.cy.auth.config;

import com.cy.core.constants.Constants;
import com.cy.redis.config.RedisSharedKeyPrefixProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 认证自动配置入口
 * <p>
 * 启用 {@link AuthProperties} 配置绑定，并声明认证模块需要跨服务共享的 Redis key 前缀。
 * Servlet 和 Reactive 的鉴权拦截器分别由
 * {@link AuthServletAutoConfiguration} 和 {@link AuthReactiveAutoConfiguration}
 * 独立注册（通过 AutoConfiguration.imports），避免在 Reactive 环境中加载 Servlet 类。
 * </p>
 *
 * @Author: pcy
 * @Date: 2026/1/12
 */
@AutoConfiguration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthAutoConfiguration {

    @Bean
    public RedisSharedKeyPrefixProvider authRedisSharedKeyPrefixProvider() {
        return () -> List.of(
            Constants.TOKEN_CACHE_KEY_PREFIX,
            Constants.USER_TOKEN_CACHE_KEY_PREFIX
        );
    }
}
