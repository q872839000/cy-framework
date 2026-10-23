package com.cy.core.config;

import com.cy.core.exception.MessageResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * 核心模块自动配置类
 *
 * @author pcy
 * @date 2026/1/7
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.cy.core")
public class CoreAutoConfiguration {

    /**
     * 注册消息解析器
     *
     * @return MessageResolver实例
     */
    @Bean
    @ConditionalOnMissingBean
    public MessageResolver messageResolver() {
        MessageResolver resolver = new MessageResolver();
        resolver.initialize();
        return resolver;
    }

}
