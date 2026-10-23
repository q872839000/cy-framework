// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.enums.SideEffectLevel;
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
 * 能力描述符
 * <p>
 * 描述 Agent 对外暴露的单项能力，Butler 在路由时根据能力描述进行粗路由匹配。
 * 每个 Agent 可声明多个能力，每个能力拥有独立的输入/输出 Schema 和意图提示。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "能力描述符")
public class CapabilityDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 能力唯一标识
     */
    @NotBlank(message = "能力ID不能为空")
    @Schema(description = "能力唯一标识")
    private String capabilityId;

    /**
     * 能力名称
     */
    @NotBlank(message = "能力名称不能为空")
    @Schema(description = "能力名称")
    private String name;

    /**
     * 能力摘要描述（供 Butler 路由和 LLM planner 使用）
     */
    @Schema(description = "能力摘要描述")
    private String summary;

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
     * 意图提示关键词列表（辅助 Butler 进行意图匹配）
     */
    @Schema(description = "意图提示关键词列表")
    private List<String> intentHints;

    /**
     * 副作用级别
     */
    @Schema(description = "副作用级别")
    @Builder.Default
    private SideEffectLevel sideEffectLevel = SideEffectLevel.NONE;

    /**
     * 是否需要用户确认（高副作用操作建议为 true）
     */
    @Schema(description = "是否需要用户确认")
    @Builder.Default
    private Boolean requiresConfirmation = false;

    /**
     * 是否幂等（同一请求多次执行结果相同）
     */
    @Schema(description = "是否幂等")
    @Builder.Default
    private Boolean idempotent = false;

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
     * 典型用法示例（供 Butler LLM planner 参考）
     */
    @Schema(description = "典型用法示例")
    private List<String> examples;

    /**
     * 可见性
     */
    @Schema(description = "可见性")
    @Builder.Default
    private SkillVisibility visibility = SkillVisibility.PUBLIC;

    /**
     * 路由标签（Butler 可按标签进行精确路由）
     */
    @Schema(description = "路由标签")
    private List<String> routingTags;
}
