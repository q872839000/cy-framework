// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.constant.OrreryConstants;
import com.cy.orrery.protocol.enums.AgentMode;
import com.cy.orrery.protocol.enums.AgentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Agent 描述符
 * <p>
 * Agent Catalog 的核心数据模型，描述 Agent 的身份、能力、技能、调用方式等语义信息。
 * Agent 通过 gRPC {@code CatalogService.UpsertAgent} 将此描述符注册到 Agent Catalog，
 * Butler 通过 Catalog Snapshot 消费并据此进行路由与编排。
 * </p>
 * <p>
 * <b>重要设计原则：</b>Agent 描述符不承担实例发现职责。
 * 内部 Agent 服务的网络实例信息由 {@link ServiceInstanceDescriptor} 统一管理，
 * Butler 通过 {@code serviceName} 关联 Service Discovery 解析实际调用目标。
 * {@code publicEndpoint} 仅供外部 Agent 或 A2A 协议直连场景使用。
 * </p>
 *
 * @author pcy
 * @date 2026/04/05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 描述符")
public class AgentDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 2L;

    /**
     * Agent 唯一标识
     */
    @NotBlank(message = "Agent ID 不能为空")
    @Schema(description = "Agent 唯一标识")
    private String agentId;

    /**
     * Agent 名称
     */
    @NotBlank(message = "Agent 名称不能为空")
    @Schema(description = "Agent 名称")
    private String name;

    /**
     * Agent 描述（面向 Butler 和管理界面的简要说明）
     */
    @Schema(description = "Agent 描述")
    private String description;

    /**
     * Agent 版本
     */
    @Schema(description = "Agent 版本")
    private String version;

    /**
     * Agent 模式
     */
    @Schema(description = "Agent 模式")
    @Builder.Default
    private AgentMode mode = AgentMode.WORKFLOW;

    /**
     * 服务名称（用于 Service Discovery 解析实际网络实例，与 spring.application.name 对齐）。
     * 内部 Agent 必须填写此字段，Butler 通过它查询健康实例后发起调用。
     */
    @Schema(description = "服务名称（用于 Service Discovery 解析实例）")
    private String serviceName;

    /**
     * 公开端点地址（仅供外部 Agent / A2A 协议直连使用）。
     * 内部 Agent 不应依赖此字段，应通过 serviceName + Service Discovery 路由。
     */
    @Schema(description = "公开端点地址（仅供外部直连 / A2A）", example = "https://agent.example.com")
    private String publicEndpoint;

    /**
     * Agent 类型（区分管家 Agent、工具 Agent、业务 Agent）
     */
    @Schema(description = "Agent 类型")
    private AgentType agentType;

    /**
     * 是否启用（管理级开关，禁用后 Butler 不再路由到此 Agent）
     */
    @Schema(description = "是否启用")
    @Builder.Default
    private Boolean enabled = true;

    /**
     * 协议版本号（与注册中心协商兼容性）
     */
    @Schema(description = "协议版本号")
    @Builder.Default
    private String protocolVersion = OrreryConstants.PROTOCOL_VERSION;

    /**
     * 能力列表（Agent 对外暴露的能力集合）
     */
    @Valid
    @Schema(description = "能力列表")
    private List<CapabilityDescriptor> capabilities;

    /**
     * 公共技能列表（可被 Butler 发现并直接调用的 Skill）
     */
    @Valid
    @Schema(description = "公共技能列表")
    private List<SkillDescriptor> publicSkills;

    /**
     * 记忆描述符
     */
    @Schema(description = "记忆描述符")
    private MemoryDescriptor memoryDescriptor;

    /**
     * 调用描述符
     */
    @Schema(description = "调用描述符")
    private InvocationDescriptor invokeDescriptor;

    /**
     * 鉴权描述符
     */
    @Schema(description = "鉴权描述符")
    private AuthDescriptor authDescriptor;

    /**
     * 是否支持直接调用（Client → Agent）
     */
    @Schema(description = "是否支持直接调用")
    @Builder.Default
    private Boolean supportsDirectInvoke = true;

    /**
     * 是否支持管家调用（Butler → Agent）
     */
    @Schema(description = "是否支持管家调用")
    @Builder.Default
    private Boolean supportsManagedInvoke = true;

    /**
     * 约束描述符
     */
    @Schema(description = "约束描述符")
    private ConstraintDescriptor constraintDescriptor;

    /**
     * 韧性描述符
     */
    @Schema(description = "韧性描述符")
    private ResilienceDescriptor resilienceDescriptor;

    /**
     * 自定义标签（用于路由、过滤和管理分组）
     */
    @Schema(description = "自定义标签")
    private List<String> tags;
}
