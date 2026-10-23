// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.enums.InvocationMode;
import com.cy.orrery.protocol.enums.InvocationPolicy;
import com.cy.orrery.protocol.enums.SkillInstallState;
import com.cy.orrery.protocol.enums.SkillKind;
import com.cy.orrery.protocol.enums.SkillOwnerType;
import com.cy.orrery.protocol.enums.SkillSource;
import com.cy.orrery.protocol.enums.SkillVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 技能描述符
 * <p>
 * 描述一个 Skill 的完整元数据，兼容 SKILL.md + frontmatter 风格的 Skill 包描述。
 * Skill 是 Agent 或 Butler 内部的可选能力单元，支持发现、安装、注册、执行等生命周期。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "技能描述符")
public class SkillDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 技能唯一标识
     */
    @NotBlank(message = "技能ID不能为空")
    @Schema(description = "技能唯一标识")
    private String skillId;

    /**
     * 技能名称
     */
    @NotBlank(message = "技能名称不能为空")
    @Schema(description = "技能名称")
    private String name;

    /**
     * 技能描述（详细说明，供 Butler LLM planner 参考）
     */
    @Schema(description = "技能描述")
    private String description;

    /**
     * 技能摘要（简短一句话描述）
     */
    @Schema(description = "技能摘要")
    private String summary;

    /**
     * 技能类型
     */
    @Schema(description = "技能类型")
    @Builder.Default
    private SkillKind kind = SkillKind.EXECUTABLE;

    /**
     * 所属实体类型
     */
    @Schema(description = "所属实体类型")
    @Builder.Default
    private SkillOwnerType ownerType = SkillOwnerType.AGENT;

    /**
     * 所属实体ID（管家服务或 Agent ID）
     */
    @Schema(description = "所属实体ID")
    private String ownerId;

    /**
     * 能力标签列表
     */
    @Schema(description = "能力标签列表")
    private List<String> capabilities;

    /**
     * 技能来源
     */
    @Schema(description = "技能来源")
    @Builder.Default
    private SkillSource source = SkillSource.BUNDLED;

    /**
     * 技能版本
     */
    @Schema(description = "技能版本")
    private String version;

    /**
     * 调用策略
     */
    @Schema(description = "调用策略")
    @Builder.Default
    private InvocationPolicy invocationPolicy = InvocationPolicy.MANAGER;

    /**
     * 调用模式
     */
    @Schema(description = "调用模式")
    @Builder.Default
    private InvocationMode invocationMode = InvocationMode.SYNC;

    /**
     * 输入参数 JSON Schema（结构化对象，对齐 OpenAI Structured Outputs 规范）
     */
    @Schema(description = "输入参数 JSON Schema")
    private Map<String, Object> inputSchema;

    /**
     * 输出结果 JSON Schema（结构化对象，对齐 OpenAI Structured Outputs 规范）
     */
    @Schema(description = "输出结果 JSON Schema")
    private Map<String, Object> outputSchema;

    /**
     * 支持的输入媒体类型
     */
    @Schema(description = "支持的输入媒体类型")
    private List<String> inputModes;

    /**
     * 支持的输出媒体类型
     */
    @Schema(description = "支持的输出媒体类型")
    private List<String> outputModes;

    /**
     * 典型用法示例
     */
    @Schema(description = "典型用法示例")
    private List<String> examples;

    /**
     * 依赖的其他技能或外部服务列表
     */
    @Schema(description = "依赖的其他技能或外部服务列表")
    private List<String> requires;

    /**
     * 可见性
     */
    @Schema(description = "可见性")
    @Builder.Default
    private SkillVisibility visibility = SkillVisibility.PRIVATE;

    /**
     * 安装状态
     */
    @Schema(description = "安装状态")
    @Builder.Default
    private SkillInstallState installState = SkillInstallState.DISCOVERED;
}
