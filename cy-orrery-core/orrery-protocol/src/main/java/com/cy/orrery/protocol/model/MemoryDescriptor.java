// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import com.cy.orrery.protocol.enums.MemoryOwner;
import com.cy.orrery.protocol.enums.MemoryVisibility;
import com.cy.orrery.protocol.enums.RetentionPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 记忆描述符
 * <p>
 * 描述 Agent 的记忆能力，包括是否支持会话记忆、长期记忆和工作记忆摘要。
 * Butler 据此决定是否向该 Agent 传递历史上下文，以及如何聚合记忆。
 * </p>
 *
 * @author pcy
 * @date 2026/03/13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "记忆描述符")
public class MemoryDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否启用记忆能力
     */
    @Schema(description = "是否启用记忆能力")
    @Builder.Default
    private Boolean enabled = false;

    /**
     * 会话记忆所有者
     */
    @Schema(description = "会话记忆所有者")
    @Builder.Default
    private MemoryOwner sessionOwner = MemoryOwner.MANAGER;

    /**
     * 工作记忆所有者
     */
    @Schema(description = "工作记忆所有者")
    @Builder.Default
    private MemoryOwner workingMemoryOwner = MemoryOwner.AGENT;

    /**
     * 长期记忆所有者
     */
    @Schema(description = "长期记忆所有者")
    @Builder.Default
    private MemoryOwner longTermMemoryOwner = MemoryOwner.MANAGER;

    /**
     * Butler 对工作记忆的可见性级别
     */
    @Schema(description = "Butler 对工作记忆的可见性级别")
    @Builder.Default
    private MemoryVisibility managerVisibility = MemoryVisibility.SUMMARY_ONLY;

    /**
     * 是否支持记忆搜索
     */
    @Schema(description = "是否支持记忆搜索")
    @Builder.Default
    private Boolean supportsSearch = false;

    /**
     * 是否支持记忆读取
     */
    @Schema(description = "是否支持记忆读取")
    @Builder.Default
    private Boolean supportsGet = false;

    /**
     * 是否支持记忆写入
     */
    @Schema(description = "是否支持记忆写入")
    @Builder.Default
    private Boolean supportsWrite = false;

    /**
     * 记忆命名空间（用于隔离不同 Agent 的记忆数据）
     */
    @Schema(description = "记忆命名空间")
    private String namespace;

    /**
     * 工作记忆摘要导出路径（Butler 可通过此路径获取只读摘要）
     */
    @Schema(description = "工作记忆摘要导出路径")
    private String exportSummaryPath;

    /**
     * 数据保留策略
     */
    @Schema(description = "数据保留策略")
    @Builder.Default
    private RetentionPolicy retentionPolicy = RetentionPolicy.SESSION_PLUS_WORKING;
}
