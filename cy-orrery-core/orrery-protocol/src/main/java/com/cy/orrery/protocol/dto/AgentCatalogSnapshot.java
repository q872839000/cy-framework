// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.dto;

import com.cy.orrery.protocol.enums.AgentMode;
import com.cy.orrery.protocol.enums.AgentType;
import com.cy.orrery.protocol.enums.SnapshotChangeType;
import com.cy.orrery.protocol.model.ResilienceDescriptor;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Agent Catalog 快照
 * <p>
 * 注册中心返回给 Butler 的全量或增量 Agent 目录快照。
 * Butler 基于快照构建本地 {@code AgentCatalogView}，据此进行能力选择与编排。
 * </p>
 * <p>
 * <b>与旧 AgentSnapshotResponse 的关键区别：</b>
 * <ul>
 *   <li>不再包含 {@code endpoint}（实例地址归 Service Discovery）</li>
 *   <li>不再包含 {@code status}（Agent 可用性由 Service Discovery 推导）</li>
 *   <li>不再包含 {@code leaseExpiresAt}（Agent 不再独立维护租约）</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/04/05
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent Catalog 快照")
public class AgentCatalogSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 快照版本号（单调递增，用于增量 watch 比较）
     */
    @Schema(description = "快照版本号")
    private Long snapshotVersion;

    /**
     * 是否为全量快照。
     * true 表示 entries 包含注册中心当前所有 Agent 的完整视图，消费方应先清空本地缓存再写入；
     * false/null 表示增量快照，消费方按 changeType 做 merge/remove。
     */
    @Schema(description = "是否为全量快照")
    @Builder.Default
    private Boolean fullSnapshot = false;

    /**
     * Agent 目录条目列表
     */
    @Schema(description = "Agent 目录条目列表")
    private List<AgentCatalogEntry> entries;

    /**
     * 快照生成时间戳（毫秒）
     */
    @Schema(description = "快照生成时间戳（毫秒）")
    private Long generatedAt;

    /**
     * Agent 目录条目（单个 Agent 的摘要信息，供 Butler 路由与规划使用）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Agent 目录条目")
    public static class AgentCatalogEntry implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Agent 唯一标识
         */
        @Schema(description = "Agent 唯一标识")
        private String agentId;

        /**
         * Agent 名称
         */
        @Schema(description = "Agent 名称")
        private String name;

        /**
         * Agent 版本
         */
        @Schema(description = "Agent 版本")
        private String version;

        /**
         * Agent 描述
         */
        @Schema(description = "Agent 描述")
        private String description;

        /**
         * Agent 模式
         */
        @Schema(description = "Agent 模式")
        private AgentMode mode;

        /**
         * 服务名称（用于 Service Discovery 解析实际调用目标）
         */
        @Schema(description = "服务名称")
        private String serviceName;

        /**
         * 公开端点地址（仅供外部直连 / A2A）
         */
        @Schema(description = "公开端点地址")
        private String publicEndpoint;

        /**
         * Agent 类型
         */
        @Schema(description = "Agent 类型")
        private AgentType agentType;

        /**
         * 是否启用
         */
        @Schema(description = "是否启用")
        private Boolean enabled;

        /**
         * Agent 级调用路径
         */
        @Schema(description = "Agent 级调用路径")
        private String invokePath;

        /**
         * 能力级调用路径模板（如 /capabilities/{capabilityId}/invoke）
         */
        @Schema(description = "能力级调用路径模板")
        private String capabilityInvokePath;

        /**
         * 是否支持直接调用
         */
        @Schema(description = "是否支持直接调用")
        private Boolean supportsDirectInvoke;

        /**
         * 是否支持管家调用
         */
        @Schema(description = "是否支持管家调用")
        private Boolean supportsManagedInvoke;

        /**
         * 能力 ID 列表
         */
        @Schema(description = "能力 ID 列表")
        private List<String> capabilityIds;

        /**
         * 公共技能 ID 列表
         */
        @Schema(description = "公共技能 ID 列表")
        private List<String> publicSkillIds;

        /**
         * 自定义标签
         */
        @Schema(description = "自定义标签")
        private List<String> tags;

        /**
         * 是否支持流式调用
         */
        @Schema(description = "是否支持流式调用")
        private Boolean supportsStreaming;

        /**
         * 韧性描述符（超时 / 并发上限等路由相关韧性参数；Butler 侧并发闸与超时截断的数据源）
         */
        @Schema(description = "韧性描述符")
        private ResilienceDescriptor resilienceDescriptor;

        /**
         * 能力摘要列表（携带结构化能力信息，供 Butler 路由决策和 Function Calling 使用）
         */
        @Schema(description = "能力摘要列表")
        private List<CapabilitySummary> capabilitySummaries;

        /**
         * 快照变更类型（增量快照中标记本条目的变更语义）
         */
        @Schema(description = "快照变更类型")
        private SnapshotChangeType changeType;
    }

    /**
     * 能力摘要（轻量级能力描述，供 Butler 路由视图和规划器消费）
     * <p>
     * 相比完整的 {@link com.cy.orrery.protocol.model.CapabilityDescriptor}，
     * 此摘要仅保留路由决策所需的关键字段，避免快照膨胀。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "能力摘要")
    public static class CapabilitySummary implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 能力唯一标识
         */
        @Schema(description = "能力唯一标识")
        private String capabilityId;

        /**
         * 能力名称
         */
        @Schema(description = "能力名称")
        private String name;

        /**
         * 能力摘要描述（一句话说明此能力做什么）
         */
        @Schema(description = "能力摘要描述")
        private String summary;

        /**
         * 意图提示词列表（辅助 Butler 做意图匹配）
         */
        @Schema(description = "意图提示词列表")
        private List<String> intentHints;

        /**
         * 输入参数 JSON Schema（供 Function Calling 构建工具定义）
         */
        @Schema(description = "输入参数 JSON Schema")
        private Map<String, Object> inputSchema;
    }
}
