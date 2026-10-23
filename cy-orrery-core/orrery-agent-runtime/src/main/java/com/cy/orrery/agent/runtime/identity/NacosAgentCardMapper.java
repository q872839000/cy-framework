// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.identity;

import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentExtension;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.cy.orrery.protocol.constant.OrreryConstants;
import com.cy.orrery.protocol.converter.AgentCardConverter;
import com.cy.orrery.protocol.model.AgentCard;
import com.cy.orrery.protocol.model.AgentDescriptor;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 协议层 AgentCard/AgentDescriptor → Nacos A2A AgentCard 映射器。
 * <p>
 * Nacos 卡片承载生态面名片（名称/版本/端点/技能摘要），完整 AgentDescriptor 以
 * {@link OrreryConstants#AGENT_CARD_CATALOG_EXTENSION_URI} extension 的 params 携带富语义副本。
 * Nacos 侧 {@code AgentCard} 与协议层 {@link AgentCard} 同名，故 Nacos 卡片类型保留全限定名引用。
 * </p>
 *
 * @author pcy
 * @since 2026/09/03
 */
final class NacosAgentCardMapper {

    private NacosAgentCardMapper() {
    }

    /**
     * 构建 Nacos A2A 卡片。
     *
     * @param descriptor     Agent 描述符
     * @param descriptorJson 描述符 JSON（extension 富语义副本）
     * @return Nacos AgentCard
     */
    static com.alibaba.nacos.api.ai.model.a2a.AgentCard toNacosCard(AgentDescriptor descriptor,
                                                                    String descriptorJson) {
        AgentCard protocolCard = AgentCardConverter.toAgentCard(descriptor);
        com.alibaba.nacos.api.ai.model.a2a.AgentCard card = new com.alibaba.nacos.api.ai.model.a2a.AgentCard();
        card.setName(descriptor.getAgentId());
        card.setVersion(descriptor.getVersion());
        card.setDescription(descriptor.getDescription());
        if (descriptor.getPublicEndpoint() != null && !descriptor.getPublicEndpoint().isBlank()) {
            card.setUrl(descriptor.getPublicEndpoint());
            card.setPreferredTransport("HTTP_JSON");
            card.setProtocolVersion(descriptor.getProtocolVersion());
        }

        AgentCapabilities capabilities = new AgentCapabilities();
        if (protocolCard.getCapabilities() != null) {
            capabilities.setStreaming(protocolCard.getCapabilities().getStreaming());
        }
        // 富语义目录副本经 extensions 携带（嵌套结构往返保真）
        AgentExtension catalogExt = new AgentExtension();
        catalogExt.setUri(OrreryConstants.AGENT_CARD_CATALOG_EXTENSION_URI);
        catalogExt.setDescription("orrery 富语义目录副本");
        catalogExt.setParams(Map.of("descriptorJson", descriptorJson));
        capabilities.setExtensions(List.of(catalogExt));
        card.setCapabilities(capabilities);

        // visibility=PUBLIC 的 capability 映射为 A2A 技能名片
        if (descriptor.getCapabilities() != null) {
            List<AgentSkill> skills = descriptor.getCapabilities().stream()
                    .map(cap -> {
                        AgentSkill skill = new AgentSkill();
                        skill.setId(cap.getCapabilityId());
                        skill.setName(cap.getName());
                        skill.setDescription(cap.getSummary());
                        skill.setTags(cap.getRoutingTags());
                        skill.setExamples(cap.getExamples());
                        return skill;
                    })
                    .collect(Collectors.toList());
            card.setSkills(skills);
        }
        return card;
    }
}
