// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.converter;

import com.cy.orrery.protocol.dto.AgentCatalogSnapshot;
import com.cy.orrery.protocol.enums.SnapshotChangeType;
import com.cy.orrery.protocol.model.AgentDescriptor;
import com.cy.orrery.protocol.model.CapabilityDescriptor;
import com.cy.orrery.protocol.model.InvocationDescriptor;
import com.cy.orrery.protocol.model.SkillDescriptor;

import java.util.List;

/**
 * AgentDescriptor → 路由快照条目转换器
 * <p>
 * Agent 的完整描述符经 Nacos A2A 卡片 extension（{@code orrery://catalog}）的
 * descriptorJson 承载，订阅方解出后投经本投影得到可路由条目，发布方与订阅方
 * 共用同一映射，避免双端漂移。
 * </p>
 *
 * @author pcy
 * @date 2026/09/03
 */
public final class AgentCatalogEntryMapper {

    private AgentCatalogEntryMapper() {
    }

    /**
     * 将 AgentDescriptor 投影为目录快照条目
     *
     * @param d          Agent 描述符
     * @param changeType 变更类型
     * @return 目录快照条目；d 为 null 时返回 null
     */
    public static AgentCatalogSnapshot.AgentCatalogEntry toEntry(AgentDescriptor d, SnapshotChangeType changeType) {
        if (d == null) {
            return null;
        }
        List<String> capabilityIds = null;
        List<AgentCatalogSnapshot.CapabilitySummary> capSummaries = null;
        if (d.getCapabilities() != null) {
            capabilityIds = d.getCapabilities().stream()
                    .map(CapabilityDescriptor::getCapabilityId)
                    .toList();
            capSummaries = d.getCapabilities().stream()
                    .map(cap -> AgentCatalogSnapshot.CapabilitySummary.builder()
                            .capabilityId(cap.getCapabilityId())
                            .name(cap.getName())
                            .summary(cap.getSummary())
                            .intentHints(cap.getIntentHints())
                            .inputSchema(cap.getInputSchema())
                            .build())
                    .toList();
        }

        List<String> publicSkillIds = null;
        if (d.getPublicSkills() != null) {
            publicSkillIds = d.getPublicSkills().stream()
                    .map(SkillDescriptor::getSkillId)
                    .toList();
        }

        InvocationDescriptor invoke = d.getInvokeDescriptor();
        boolean streaming = invoke != null && Boolean.TRUE.equals(invoke.getStreaming());

        return AgentCatalogSnapshot.AgentCatalogEntry.builder()
                .agentId(d.getAgentId())
                .name(d.getName())
                .version(d.getVersion())
                .description(d.getDescription())
                .mode(d.getMode())
                .serviceName(d.getServiceName())
                .publicEndpoint(d.getPublicEndpoint())
                .agentType(d.getAgentType())
                .enabled(d.getEnabled())
                .invokePath(invoke != null ? invoke.getInvokePath() : null)
                .capabilityInvokePath(invoke != null ? invoke.getPublicCapabilityInvokePath() : null)
                .supportsDirectInvoke(d.getSupportsDirectInvoke())
                .supportsManagedInvoke(d.getSupportsManagedInvoke())
                .capabilityIds(capabilityIds)
                .publicSkillIds(publicSkillIds)
                .tags(d.getTags())
                .supportsStreaming(streaming)
                .resilienceDescriptor(d.getResilienceDescriptor())
                .capabilitySummaries(capSummaries)
                .changeType(changeType)
                .build();
    }
}
