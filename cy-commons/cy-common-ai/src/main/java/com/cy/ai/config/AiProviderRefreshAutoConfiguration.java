package com.cy.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * AI Provider 热刷新自动配置
 * <p>
 * 仅在 classpath 存在 spring-cloud-context（配置中心客户端，如 Nacos Config）时生效：
 * 监听 {@link EnvironmentChangeEvent}（配置中心推送后由 Spring Cloud 刷新上下文时发布），
 * 从 {@link Environment} 重新绑定 {@code cy.ai} 全量配置（远程层与本地层已按属性源
 * 优先级合并），驱动 {@link AiProviderRegistry} 做 diff 热更新。
 * </p>
 * <p>
 * 无配置中心环境（独立运行的 agent）：本配置整体不装配，注册表仅启动时加载一次本地配置。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@AutoConfiguration(after = AiAutoConfiguration.class)
@ConditionalOnClass(EnvironmentChangeEvent.class)
@ConditionalOnBean(AiProviderRegistry.class)
public class AiProviderRefreshAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiProviderRefreshAutoConfiguration.class);

    /**
     * 配置变更监听器：cy.ai 前缀相关 key 变更（或全量刷新）时重建注册表快照
     *
     * @param environment 当前环境（远程层属性已合并）
     * @param registry    Provider 动态注册表
     * @return 监听器实例
     */
    @Bean
    public ApplicationListener<EnvironmentChangeEvent> aiProviderRefreshListener(
            Environment environment, AiProviderRegistry registry) {
        return event -> {
            if (!isAiConfigAffected(event)) {
                return;
            }
            Binder.get(environment).bind(AiProperties.PREFIX, AiProperties.class)
                    .ifBound(registry::refresh);
        };
    }

    /**
     * 本次变更是否涉及 cy.ai 配置
     * <p>
     * 事件未携带具体 key（全量刷新场景）时按涉及处理——注册表 diff 本身幂等，
     * 无实际变更时仅一次快照重建，代价可忽略。
     * </p>
     */
    private boolean isAiConfigAffected(EnvironmentChangeEvent event) {
        if (event.getKeys() == null || event.getKeys().isEmpty()) {
            return true;
        }
        String prefix = AiProperties.PREFIX + ".";
        return event.getKeys().stream().anyMatch(key -> key.startsWith(prefix));
    }
}
