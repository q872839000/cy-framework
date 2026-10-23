package com.cy.conversation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 智能会话组件配置属性
 *
 * @author pcy
 * @date 2026/1/26
 */
@Data
@ConfigurationProperties(prefix = "cy.conversation")
public class ConversationProperties {

    /**
     * 是否启用智能会话组件
     */
    private boolean enabled = true;

    /**
     * 是否启用表自动初始化
     */
    private boolean tableInit = true;

    /**
     * 指标采集配置
     */
    private MetricsConfig metrics = new MetricsConfig();


    /**
     * 指标采集配置类
     */
    @Data
    public static class MetricsConfig {

        /**
         * 是否启用指标采集
         */
        private boolean enabled = true;
    }
}
