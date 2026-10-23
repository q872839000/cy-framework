package com.cy.system.config;

import com.cy.system.initializer.TableInitializer;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import javax.sql.DataSource;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 系统管理模块自动配置类
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "cy.system", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SystemProperties.class)
@ComponentScan(basePackages = "com.cy.system")
@MapperScan(basePackages = "com.cy.system.**.dao")
public class SystemAutoConfiguration {

    /**
     * 注册表初始化器
     *
     * @param properties 系统配置属性
     * @param dataSource 数据源
     * @return 表初始化器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.system", name = "table-init", havingValue = "true", matchIfMissing = true)
    public TableInitializer tableInitializer(SystemProperties properties, DataSource dataSource) {
        return new TableInitializer(properties, dataSource);
    }
}
