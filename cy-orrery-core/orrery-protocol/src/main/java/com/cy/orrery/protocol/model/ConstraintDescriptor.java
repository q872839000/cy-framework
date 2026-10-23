// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 约束描述符
 * <p>
 * 描述 Agent 的运行约束与限制条件，Butler 在编排时据此
 * 进行确认决策、委托深度控制和调用策略约束。
 * </p>
 *
 * @author pcy
 * @date 2026/03/14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "约束描述符")
public class ConstraintDescriptor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 允许的任务类型列表
     */
    @Schema(description = "允许的任务类型列表")
    private List<String> allowedTaskKinds;

    /**
     * 需要确认的操作类型列表（如 deploy、remove、batch-modify）
     */
    @Schema(description = "需要确认的操作类型列表")
    private List<String> requiresConfirmationFor;

    /**
     * 最大委托深度（0 表示不允许 Agent 再调其他 Agent）
     */
    @Schema(description = "最大委托深度")
    @Builder.Default
    private Integer maxDelegationDepth = 0;
}
