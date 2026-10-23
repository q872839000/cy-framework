// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.converter;

import com.cy.orrery.protocol.model.AgentCard;
import com.cy.orrery.protocol.model.AgentDescriptor;
import com.cy.orrery.protocol.model.CapabilityDescriptor;
import com.cy.orrery.protocol.model.SkillDescriptor;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentDescriptor → AgentCard 转换器
 * <p>
 * 将内部 AgentDescriptor 转换为 A2A 协议兼容的 Agent Card，
 * 用于 {@code /.well-known/agent-card.json} 端点输出和注册中心代理查询。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
public final class AgentCardConverter {

    private AgentCardConverter() {
    }

    /**
     * 将 AgentDescriptor 转换为 A2A AgentCard
     *
     * @param descriptor Agent 描述符
     * @return A2A Agent Card
     */
    public static AgentCard toAgentCard(AgentDescriptor descriptor) {
        if (descriptor == null) {
            return null;
        }

        // 构建 supportedInterfaces（仅在有公开端点时生成，内部 Agent 通过 serviceName 路由）
        List<AgentCard.SupportedInterface> interfaces = new ArrayList<>();
        String cardUrl = descriptor.getPublicEndpoint();
        if (cardUrl != null && !cardUrl.isBlank()) {
            interfaces.add(AgentCard.SupportedInterface.builder()
                    .url(cardUrl)
                    .protocolBinding("HTTP_JSON")
                    .protocolVersion(descriptor.getProtocolVersion())
                    .build());
        }

        // 构建 capabilities
        boolean streaming = false;
        if (descriptor.getInvokeDescriptor() != null) {
            streaming = Boolean.TRUE.equals(descriptor.getInvokeDescriptor().getStreaming());
        }
        AgentCard.Capabilities capabilities = AgentCard.Capabilities.builder()
                .streaming(streaming)
                .pushNotifications(false)
                .stateTransitionHistory(false)
                .build();

        // 构建 skills（从 capabilities + publicSkills 合并）
        List<AgentCard.SkillSummary> skills = new ArrayList<>();
        if (descriptor.getCapabilities() != null) {
            for (CapabilityDescriptor cap : descriptor.getCapabilities()) {
                skills.add(AgentCard.SkillSummary.builder()
                        .id(cap.getCapabilityId())
                        .name(cap.getName())
                        .description(cap.getSummary())
                        .tags(cap.getRoutingTags())
                        .examples(cap.getExamples())
                        .inputModes(cap.getInputModes())
                        .outputModes(cap.getOutputModes())
                        .build());
            }
        }
        if (descriptor.getPublicSkills() != null) {
            for (SkillDescriptor skill : descriptor.getPublicSkills()) {
                skills.add(AgentCard.SkillSummary.builder()
                        .id(skill.getSkillId())
                        .name(skill.getName())
                        .description(skill.getDescription())
                        .tags(skill.getCapabilities())
                        .examples(skill.getExamples())
                        .inputModes(skill.getInputModes())
                        .outputModes(skill.getOutputModes())
                        .build());
            }
        }

        // 默认媒体类型
        List<String> defaultInputModes = List.of("application/json", "text/plain");
        List<String> defaultOutputModes = List.of("application/json");
        if (streaming) {
            defaultOutputModes = List.of("application/json", "text/event-stream");
        }

        // 鉴权类型
        String authType = "none";
        if (descriptor.getAuthDescriptor() != null && descriptor.getAuthDescriptor().getAuthType() != null) {
            authType = descriptor.getAuthDescriptor().getAuthType();
        }

        return AgentCard.builder()
                .name(descriptor.getName())
                .description(descriptor.getDescription())
                .version(descriptor.getVersion())
                .supportedInterfaces(interfaces)
                .capabilities(capabilities)
                .skills(skills)
                .defaultInputModes(defaultInputModes)
                .defaultOutputModes(defaultOutputModes)
                .authType(authType)
                .build();
    }
}
