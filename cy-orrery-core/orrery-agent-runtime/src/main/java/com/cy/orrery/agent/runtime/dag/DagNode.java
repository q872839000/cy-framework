// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.core.exception.CYException;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * DAG 节点：领域无关的最小节点契约。
 * <p>
 * 引擎只依赖「节点 ID + 依赖列表」做拓扑调度；节点做什么（工具调用 / 子 Agent /
 * 外部 Agent / 人工门）由宿主经 {@link DagNodeHandler} 注入语义。宿主的扩展字段
 * （类型 / 入参模板 / 验收条件等）不进本记录——以「节点 ID → 领域节点」查找表
 * 在处理器内关联（record 不可继承是有意约束：引擎契约不被领域字段撑大）。
 * </p>
 *
 * @param nodeId            节点 ID（图内唯一，勾稽与事件引用的稳定键）
 * @param description       节点描述（领域可读的任务说明）
 * @param dependsOn         依赖的上游节点 ID 列表（空表示源节点）
 * @param depFailurePolicy  上游失败时的本节点策略（null 视为 DROP）
 * @author pcy
 */
public record DagNode(String nodeId, String description, List<String> dependsOn,
                      DepFailurePolicy depFailurePolicy) {

    /**
     * 上游失败策略（fan-in 语义的节点级表达）。
     */
    public enum DepFailurePolicy {

        /** 上游失败即放弃本节点（默认；级联放弃并继续传染下游） */
        DROP,

        /**
         * 上游失败仍执行（best-effort）：本节点就绪条件放宽为「上游全部到终态」，
         * 失败上游的结果以 Failed 形态出现在 dependencyOutcomes 视图中，
         * 由处理器决定如何使用（如聚合节点以错误占位参与汇总）。
         */
        RUN_WITH_ERROR
    }

    /**
     * 紧凑构造：依赖去重（保序）、防御性拷贝、非空校验。
     */
    public DagNode {
        if (nodeId == null || nodeId.isBlank()) {
            throw new CYException(50501, "nodeId 为空");
        }
        dependsOn = dependsOn == null ? List.of()
                : List.copyOf(new LinkedHashSet<>(dependsOn));
        if (depFailurePolicy == null) {
            depFailurePolicy = DepFailurePolicy.DROP;
        }
    }

    /**
     * 兼容构造（默认 DROP 策略）。
     *
     * @param nodeId      节点 ID
     * @param description 节点描述
     * @param dependsOn   依赖列表
     */
    public DagNode(String nodeId, String description, List<String> dependsOn) {
        this(nodeId, description, dependsOn, DepFailurePolicy.DROP);
    }

    /**
     * 源节点工厂（无依赖）。
     *
     * @param nodeId      节点 ID
     * @param description 节点描述
     * @return 节点
     */
    public static DagNode of(String nodeId, String description) {
        return new DagNode(nodeId, description, List.of());
    }
}
