// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.assets;

import com.alibaba.nacos.api.ai.AiFactory;
import com.cy.orrery.agent.runtime.config.NacosClientSupport;
import com.cy.orrery.agent.runtime.config.OrreryAgentRuntimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 提示词资产自动装配。
 * <p>
 * {@code cy.orrery.agent-runtime.assets.enabled=true} 且类路径存在 nacos-client 时装配
 * Nacos 通道解析器；否则装配纯 classpath 解析器（行为与迁移前等价）。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
@AutoConfiguration
@ConditionalOnClass(name = "com.alibaba.nacos.api.ai.AiService")
@EnableConfigurationProperties(OrreryAgentRuntimeProperties.class)
public class AssetsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AssetsAutoConfiguration.class);

    /**
     * 提示词资产解析器（加载点统一入口）。
     *
     * @param properties  运行时配置
     * @param environment 环境（读取 spring.cloud.nacos.* 共享配置）
     * @return 资产解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public PromptAssetResolver promptAssetResolver(OrreryAgentRuntimeProperties properties,
                                                   Environment environment) {
        OrreryAgentRuntimeProperties.AssetsSpec spec = properties.getAssets();
        // Key 首段：显式配置优先，缺省取服务名。不回调 AgentDescriptor Bean——
        // 描述符由宿主工具注册表组装，而加载点（工具链上游）构造期即调本解析器，正向依赖成环。
        String agentId = !spec.getAgentId().isBlank() ? spec.getAgentId()
                : environment.getProperty("spring.application.name", "agent");
        PromptAssetProvider provider = spec.isEnabled() ? buildProvider(environment) : null;
        return new PromptAssetResolver(provider, agentId, spec.getLabel());
    }

    /** 构建 Nacos 资产提供者（远端不可达返回 null，调用方走 classpath 兜底）。
     *  命名空间取 {@code nacos.config.namespace}（提示词资产是配置域数据）；
     *  与 identity 通道取 {@code nacos.discovery.namespace}（端点是发现域数据）是刻意分流。 */
    private static PromptAssetProvider buildProvider(Environment environment) {
        try {
            return new NacosPromptAssetProvider(AiFactory.createAiService(
                    NacosClientSupport.toClientProperties(
                            environment.getProperty("spring.cloud.nacos.server-addr"),
                            environment.getProperty("spring.cloud.nacos.username"),
                            environment.getProperty("spring.cloud.nacos.password"),
                            environment.getProperty("spring.cloud.nacos.config.namespace"))));
        } catch (Exception e) {
            log.warn("Nacos 提示词资产通道初始化失败，降级纯 classpath 解析: {}", e.getMessage());
            return null;
        }
    }
}
