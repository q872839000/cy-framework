// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.AttributeKey;
import java.util.HashMap;
import java.util.Map;

/**
 * DAG 跨挂起状态的属性存取：节点结果与执行预算随检查点往返。
 * <p>
 * 与 {@code PipelineAttributes} 同一意图：挂起（AWAITING）后宿主重新调
 * {@code DagEngine.run()} 时从属性恢复已得结果与执行计数——已完成节点不重跑、
 * 预算不重新武装。属性值一律为 Jackson 可序列化的基础结构（Map/String/Number），
 * 保证经检查点序列化通道往返不丢信息。
 * </p>
 *
 * @author pcy
 */
public final class DagAttributes {

    /** 节点结果属性键（值：节点 ID → 结果快照 Map）。 */
    private static final AttributeKey<Map> RESULTS = new AttributeKey<>("dag.results", Map.class);

    /** 执行计数属性键（跨挂起累计的处理器调用次数）。
     *  键类型用 Number 而非 Integer：检查点 JSON 往返的数值还原类型取决于宿主
     *  ObjectMapper 特性（如 USE_LONG_FOR_INTS 会还原为 Long），按 Number 宽容读取。 */
    private static final AttributeKey<Number> EXECUTIONS =
            new AttributeKey<>("dag.executions", Number.class);

    private DagAttributes() {
    }

    /**
     * 暂存节点结果（每次结算后覆盖为最新全量视图）。
     *
     * @param ctx     运行上下文
     * @param results 节点 ID → 结果
     */
    static void stashResults(AgentRunContext ctx, Map<String, DagNodeOutcome> results) {
        Map<String, Map<String, Object>> snapshot = new HashMap<>();
        results.forEach((nodeId, outcome) -> snapshot.put(nodeId, toSnapshot(outcome)));
        ctx.putAttr(RESULTS, snapshot);
    }

    /**
     * 恢复节点结果（无历史为空 Map）。
     *
     * @param ctx 运行上下文
     * @return 节点 ID → 结果
     */
    @SuppressWarnings("unchecked")
    static Map<String, DagNodeOutcome> restoreResults(AgentRunContext ctx) {
        Map<?, ?> snapshot = ctx.getAttr(RESULTS);
        Map<String, DagNodeOutcome> results = new HashMap<>();
        if (snapshot == null) {
            return results;
        }
        // 检查点 JSON 往返后值为通用 Map（未注册类型的宿主属性如此还原），按键取值宽容解析
        snapshot.forEach((nodeId, value) -> {
            if (nodeId instanceof String id && value instanceof Map) {
                results.put(id, fromSnapshot((Map<String, Object>) value));
            }
        });
        return results;
    }

    /**
     * 暂存执行计数。
     *
     * @param ctx        运行上下文
     * @param executions 累计执行次数
     */
    static void stashExecutions(AgentRunContext ctx, int executions) {
        ctx.putAttr(EXECUTIONS, executions);
    }

    /**
     * 恢复执行计数（无历史为 0）。
     *
     * @param ctx 运行上下文
     * @return 累计执行次数
     */
    static int restoreExecutions(AgentRunContext ctx) {
        Number value = ctx.getAttr(EXECUTIONS);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 终态清理（同上下文复用时防残留；由运行门面在终态结算时调用）。
     *
     * @param ctx 运行上下文
     */
    public static void clear(AgentRunContext ctx) {
        ctx.putAttr(RESULTS, null);
        ctx.putAttr(EXECUTIONS, null);
    }

    /**
     * 结果 → 可序列化快照。
     *
     * @param outcome 节点结果
     * @return 快照 Map
     */
    private static Map<String, Object> toSnapshot(DagNodeOutcome outcome) {
        Map<String, Object> map = new HashMap<>();
        if (outcome instanceof DagNodeOutcome.Done done) {
            map.put("status", "DONE");
            map.put("evidence", done.evidence());
        } else if (outcome instanceof DagNodeOutcome.Failed failed) {
            map.put("status", "FAILED");
            map.put("code", failed.code());
            map.put("message", failed.message());
            map.put("retryable", failed.retryable());
        } else if (outcome instanceof DagNodeOutcome.Dropped dropped) {
            map.put("status", "DROPPED");
            map.put("reason", dropped.reason());
        }
        return map;
    }

    /**
     * 快照 → 结果。
     *
     * @param map 快照 Map
     * @return 节点结果
     */
    private static DagNodeOutcome fromSnapshot(Map<String, Object> map) {
        Object status = map.get("status");
        if ("DONE".equals(status)) {
            return DagNodeOutcome.Done.of((String) map.get("evidence"));
        }
        if ("FAILED".equals(status)) {
            Object retryable = map.get("retryable");
            return DagNodeOutcome.Failed.of((String) map.get("code"), (String) map.get("message"),
                    Boolean.TRUE.equals(retryable));
        }
        return new DagNodeOutcome.Dropped((String) map.get("reason"));
    }
}
