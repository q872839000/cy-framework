// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.identity;

import com.cy.orrery.agent.runtime.config.OrreryAgentRuntimeProperties;
import com.cy.orrery.protocol.model.AgentDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Agent 身份发布自动装配。
 * <p>
 * 开关 {@code cy.orrery.agent-runtime.identity.enabled=true} 且类路径存在 nacos-client
 * （AiService）时装配；宿主以 Bean 形式声明 {@link AgentDescriptor}（单 Agent 一个，
 * 多 Agent 多个）。发布走 Nacos 原生 A2A 通道（卡片 + 端点 + 心跳续期）。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
@ConditionalOnClass(name = "com.alibaba.nacos.api.ai.AiService")
@ConditionalOnProperty(prefix = "cy.orrery.agent-runtime.identity", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OrreryAgentRuntimeProperties.class)
public class IdentityAutoConfiguration {

    /**
     * Agent 身份发布器。
     *
     * @param descriptorProvider 宿主声明的 AgentDescriptor（0..N 个）
     * @param properties         运行时配置
     * @param objectMapper       JSON 编解码器
     * @param environment        环境（读取 spring.cloud.nacos.* 共享配置）
     * @return 身份发布器
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentIdentityPublisher agentIdentityPublisher(ObjectProvider<AgentDescriptor> descriptorProvider,
                                                         OrreryAgentRuntimeProperties properties,
                                                         ObjectMapper objectMapper,
                                                         Environment environment) {
        return new AgentIdentityPublisher(descriptorProvider, properties, objectMapper, environment);
    }
}
