// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import java.util.Map;

/**
 * DAG 节点处理器 SPI：节点语义的宿主注入点。
 * <p>
 * 引擎托管的部分（领域无关）：拓扑分层、同层并行、并发上限、节点超时、
 * 失败级联放弃、挂起结算、执行预算熔断、取消检查点。
 * 节点做什么、何时算完成 / 失败 / 挂起，由本接口表达——与
 * {@code PipelineEngine} / {@code StageHandler} 同一分工模式。
 * </p>
 * <p>
 * 调用线程约定：同层节点在虚拟线程池并行调用本接口，实现必须线程安全、
 * 不得直接写 {@code ctx.getMessages()}（消息列表只归引擎 / 主线程写，
 * 见 {@link AgentRunContext} 线程约定）；跨节点数据经返回值与
 * {@code dependencyOutcomes} 传递。
 * </p>
 *
 * @param <N> 节点类型（宿主可固定为 {@link DagNode}；领域扩展字段经「节点 ID →
 *            领域节点」查找表关联，不经类型层级表达）
 * @author pcy
 */
@FunctionalInterface
public interface DagNodeHandler<N extends DagNode> {

    /**
     * 执行一个节点。
     *
     * @param ctx                运行上下文（取消检查、事件、属性表）
     * @param node               当前节点
     * @param dependencyOutcomes 全部已完成上游节点的结果视图（含未列为直接依赖的
     *                           更早节点——跨层数据引用由宿主自行决定可见范围；
     *                           视图只读，键为节点 ID）
     * @return 节点结果（{@link DagNodeOutcome.Done} / {@link DagNodeOutcome.Failed} /
     *         {@link DagNodeOutcome.Awaiting} 之一）
     */
    DagNodeOutcome handle(AgentRunContext ctx, N node, Map<String, DagNodeOutcome> dependencyOutcomes);
}
