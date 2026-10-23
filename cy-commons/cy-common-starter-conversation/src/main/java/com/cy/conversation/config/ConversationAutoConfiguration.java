package com.cy.conversation.config;

import com.cy.conversation.initializer.ConversationTableInitializer;
import com.cy.conversation.metrics.ConversationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import javax.sql.DataSource;

/**
 * 智能会话组件自动配置类
 * <p>
 * 负责自动装配组件所需的各种Bean，包括：
 * - 表初始化器
 * - 指标采集器
 * </p>
 * <p>
 * AI 客户端（AiFacade）由 cy-common-ai 模块的 AiAutoConfiguration 自动装配。
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "cy.conversation", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConversationProperties.class)
@ComponentScan(basePackages = "com.cy.conversation")
@MapperScan(basePackages = "com.cy.conversation.**.dao")
public class ConversationAutoConfiguration {

    /**
     * 注册表初始化器
     *
     * @param properties 配置属性
     * @param dataSource 数据源
     * @return 表初始化器实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.conversation", name = "table-init", havingValue = "true", matchIfMissing = true)
    public ConversationTableInitializer conversationTableInitializer(ConversationProperties properties,
                                                                      DataSource dataSource) {
        return new ConversationTableInitializer(properties, dataSource);
    }

    /**
     * 注册指标采集器（有MeterRegistry时启用完整指标采集）
     *
     * @param meterRegistry Micrometer注册器
     * @return 指标采集器实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "cy.conversation.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConversationMetrics conversationMetrics(MeterRegistry meterRegistry) {
        return new ConversationMetrics(meterRegistry);
    }

    /**
     * 注册空操作指标采集器（无MeterRegistry时使用）
     *
     * @return 空操作指标采集器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public ConversationMetrics noopConversationMetrics() {
        return new ConversationMetrics();
    }
}
