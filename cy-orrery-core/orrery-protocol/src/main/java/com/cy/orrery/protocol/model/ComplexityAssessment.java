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

/**
 * 任务复杂度评估结果
 * <p>
 * Butler 认知层的分级判定产出：语义判定由 LLM 单路完成（StructuredOutputInvoker 约束
 * JSON 输出），本模型同时是 LLM 输出 Schema 的宿主映射与对前端 / 管理端的线协议模型。
 * 判定理由（rationale）必须落库（butler_task），供误判复盘与提示词迭代。
 * </p>
 *
 * @author pcy
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务复杂度评估结果")
public class ComplexityAssessment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 复杂度档位
     */
    @Schema(description = "复杂度档位")
    private Level level;

    /**
     * 判定理由（LLM 自陈述；落库供误判复盘）
     */
    @Schema(description = "判定理由")
    private String rationale;

    /**
     * 是否需要计划确认（高风险 / 不可逆 / 周期任务时 true）
     */
    @Schema(description = "是否需要计划确认")
    @Builder.Default
    private Boolean needsPlanConfirmation = false;

    /**
     * 复杂度档位。
     */
    public enum Level {

        /** 直接应答：闲聊 / 纯知识问答，单次 LLM 调用，不进循环 */
        L0_DIRECT,

        /** 单循环：单目标少量步骤，走 ReAct 主循环 */
        L1_SINGLE_LOOP,

        /** 计划执行：多步骤 / 有依赖 / 中风险，先出计划再执行 */
        L2_PLAN_EXECUTE,

        /** 编排：≥2 个 Agent 或含并行成分，DAG 编排执行 */
        L3_ORCHESTRATE
    }
}
