package com.cy.ai.config;

import com.cy.ai.controller.AiCatalogController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * AI 模型目录接口自动配置
 * <p>
 * Web 应用（Servlet 或 Reactive）且存在 {@link AiProviderRegistry} 时装配
 * {@link AiCatalogController}。可通过 {@code cy.ai.catalog.enabled=false} 关闭。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@AutoConfiguration(after = AiAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnBean(AiProviderRegistry.class)
@ConditionalOnProperty(prefix = "cy.ai.catalog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AiCatalogAutoConfiguration {

    /**
     * 注册模型目录控制器
     *
     * @param registry Provider 动态注册表
     * @return 控制器实例
     */
    @Bean
    @ConditionalOnMissingBean
    public AiCatalogController aiCatalogController(AiProviderRegistry registry) {
        return new AiCatalogController(registry);
    }
}
