// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.orrery.agent.runtime.constant.RuntimeErrorCode;
import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.context.RunCancellationException;
import com.cy.orrery.agent.runtime.event.Events;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DAG 引擎：节点拓扑分层 + 同层并行的调度内核（Graph 形态执行内核，
 * 与 {@code ReactLoopEngine} / {@code PipelineEngine} 平级）。
 * <p>
 * 引擎托管的部分（领域无关）：图校验（缺失依赖 / 环 / 重复 ID）、Kahn 拓扑分层、
 * 同层虚拟线程并行（信号量限并发）、单节点超时、失败级联放弃下游、挂起结算
 * （同层在途节点跑完后以 AWAITING 返回）、执行预算熔断（跨挂起累计不重新武装）、
 * 取消检查点。节点语义由 {@link DagNodeHandler} 注入。
 * </p>
 * <p>
 * 跨挂起恢复：已得结果与执行计数经 {@link DagAttributes} 存于运行上下文属性
 * （随检查点往返），恢复后重入 {@code run()} 时已完成节点不重跑。
 * 引擎本身无状态可单例复用。
 * </p>
 *
 * @author pcy
 */
public class DagEngine {

    private static final Logger log = LoggerFactory.getLogger(DagEngine.class);

    /**
     * 运行 DAG（计数自上下文属性恢复，支持挂起后续跑）。
     *
     * @param ctx     运行上下文
     * @param nodes   节点列表（顺序无关，拓扑由 dependsOn 决定）
     * @param handler 节点处理器
     * @param config  执行配置
     * @param <N>     节点类型
     * @return 执行结果
     * @throws RunCancellationException 运行被取消
     */
    public <N extends DagNode> DagOutcome run(AgentRunContext ctx, List<N> nodes,
                                              DagNodeHandler<N> handler, DagConfig config) {
        Map<String, DagNodeOutcome> results = DagAttributes.restoreResults(ctx);
        int executions = DagAttributes.restoreExecutions(ctx);

        // 图校验失败视为宿主计划缺陷：不消耗执行预算，直接 FAILED（不可重试）
        String validationError = validate(nodes, results.keySet());
        if (validationError != null) {
            return new DagOutcome(DagOutcome.Status.FAILED, Map.of(), 0, 0,
                    null, RuntimeErrorCode.DAG_GRAPH_INVALID.code(),
                    RuntimeErrorCode.DAG_GRAPH_INVALID.format(validationError), false);
        }

        Map<String, N> pending = new HashMap<>();
        for (N node : nodes) {
            if (!results.containsKey(node.nodeId())) {
                pending.put(node.nodeId(), node);
            }
        }

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            while (!pending.isEmpty()) {
                throwIfCancelled(ctx, "dag_schedule");
                List<N> ready = readyNodes(pending, results);
                if (ready.isEmpty()) {
                    // 就绪集为空但仍有待执行节点：只可能是校验遗漏的不可达图，按图缺陷失败
                    // （不能用预算熔断的错误码误导排障方向）
                    log.error("DAG 无就绪节点但仍有待执行节点（图校验遗漏？）runId={} pending={}",
                            ctx.getRunId(), pending.keySet());
                    return new DagOutcome(DagOutcome.Status.FAILED, Map.copyOf(results),
                            executions, droppedCount(results), null,
                            RuntimeErrorCode.DAG_GRAPH_INVALID.code(),
                            RuntimeErrorCode.DAG_GRAPH_INVALID.format(
                                    "存在不可达节点: " + pending.keySet()), false);
                }
                // 预算以层为粒度检查：已有执行计数触顶且仍有工作 → 熔断；
                // 末层恰好用尽预算仍可正常完成（预算防的是挂起-恢复 / 重试风暴，不误伤正常收官）
                if (executions >= config.maxNodeExecutions()) {
                    return fuseBudget(ctx, results, executions, droppedCount(results));
                }

                LevelRun levelRun = runLevel(ctx, ready, handler, config, results,
                        executions, executor);
                executions = levelRun.executions();
                for (N node : ready) {
                    pending.remove(node.nodeId());
                }
                cascadeDrop(pending, results, nodes);
                DagAttributes.stashResults(ctx, results);
                DagAttributes.stashExecutions(ctx, executions);

                if (levelRun.awaiting() != null) {
                    DagNodeOutcome.Awaiting awaiting = levelRun.awaiting();
                    log.info("DAG 挂起 runId={} awaitingKey={}", ctx.getRunId(), awaiting.awaitingKey());
                    return DagOutcome.awaiting(results, executions, droppedCount(results),
                            awaiting.awaitingKey(), awaiting.message());
                }
            }
            return DagOutcome.settled(results, executions, droppedCount(results), nodes.size());
        } finally {
            executor.close();
        }
    }

    /**
     * 执行一个拓扑层（层内节点并行）。
     *
     * @param ctx        运行上下文
     * @param ready      本层就绪节点
     * @param handler    节点处理器
     * @param config     执行配置
     * @param results    全量结果表（本层就地写入）
     * @param executions 已执行计数（本层之前）
     * @param executor   虚拟线程执行器
     * @return 本层执行报告
     */
    private <N extends DagNode> LevelRun runLevel(AgentRunContext ctx, List<N> ready,
                                                  DagNodeHandler<N> handler, DagConfig config,
                                                  Map<String, DagNodeOutcome> results,
                                                  int executions, ExecutorService executor) {
        Semaphore permits = new Semaphore(config.maxConcurrency());
        Map<String, Future<DagNodeOutcome>> futures = new HashMap<>();
        Map<String, Integer> stepIndexes = new HashMap<>();
        for (N node : ready) {
            throwIfCancelled(ctx, "dag_node_" + node.nodeId());
            int stepIndex = ctx.nextStepIndex();
            stepIndexes.put(node.nodeId(), stepIndex);
            Events.stepStarted(ctx.getBus(), stepIndex, Map.of("node", node.nodeId()));
            Map<String, DagNodeOutcome> dependencyView = Map.copyOf(results);
            futures.put(node.nodeId(), executor.submit(() -> {
                permits.acquire();
                try {
                    return handler.handle(ctx, node, dependencyView);
                } finally {
                    permits.release();
                }
            }));
        }

        DagNodeOutcome.Awaiting awaiting = null;
        int executed = executions;
        for (N node : ready) {
            DagNodeOutcome outcome = awaitNode(ctx, node, futures.get(node.nodeId()), config);
            executed++;
            // 处理器只允许 Done/Failed/Awaiting：Dropped 属引擎内部语义，误返回按失败兜底
            if (outcome instanceof DagNodeOutcome.Dropped) {
                outcome = DagNodeOutcome.Failed.of(RuntimeErrorCode.DAG_NODE_OUTCOME_INVALID.code(),
                        RuntimeErrorCode.DAG_NODE_OUTCOME_INVALID.format(node.nodeId()), false);
            }
            if (outcome instanceof DagNodeOutcome.Awaiting a) {
                // 挂起不落结果（保持「results 仅含终态」不变量；恢复后由处理器重跑）；
                // 同层多个挂起取首个为挂起键，其余保持待执行（下轮重入再挂起）
                if (awaiting == null) {
                    awaiting = a;
                }
                continue;
            }
            results.put(node.nodeId(), outcome);
            if (outcome instanceof DagNodeOutcome.Failed failed) {
                log.warn("DAG 节点失败 runId={} node={} code={} message={}",
                        ctx.getRunId(), node.nodeId(), failed.code(), failed.message());
            }
        }
        for (N node : ready) {
            Events.stepFinished(ctx.getBus(), stepIndexes.get(node.nodeId()), true,
                    Map.of("node", node.nodeId(),
                            "status", outcomeStatus(results.get(node.nodeId()), awaiting, node)));
        }
        return new LevelRun(awaiting, executed);
    }

    /**
     * 等待单节点结果（超时强取消；取消检查）。
     *
     * @param ctx     运行上下文
     * @param node    节点
     * @param future  节点任务
     * @param config  执行配置
     * @return 节点结果
     */
    private <N extends DagNode> DagNodeOutcome awaitNode(AgentRunContext ctx, N node,
                                                         Future<DagNodeOutcome> future,
                                                         DagConfig config) {
        try {
            return config.nodeTimeoutMs() > 0
                    ? future.get(config.nodeTimeoutMs(), TimeUnit.MILLISECONDS)
                    : future.get();
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("DAG 节点超时强取消 runId={} node={} timeoutMs={}",
                    ctx.getRunId(), node.nodeId(), config.nodeTimeoutMs());
            return DagNodeOutcome.Failed.of(RuntimeErrorCode.DAG_NODE_TIMEOUT.code(),
                    RuntimeErrorCode.DAG_NODE_TIMEOUT.format(node.nodeId(), config.nodeTimeoutMs()),
                    true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new RunCancellationException(ctx.getRunId(), "dag_await_" + node.nodeId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RunCancellationException cancellation) {
                throw cancellation;
            }
            log.error("DAG 节点处理器未捕获异常 runId={} node={}",
                    ctx.getRunId(), node.nodeId(), cause);
            return DagNodeOutcome.Failed.of(RuntimeErrorCode.DAG_NODE_HANDLER_ERROR.code(),
                    RuntimeErrorCode.DAG_NODE_HANDLER_ERROR.format(node.nodeId(),
                            cause == null ? "unknown" : cause.getMessage()),
                    false);
        }
    }

    /**
     * 上游失败 / 被放弃时级联放弃下游（传递闭包）。
     * <p>
     * 声明 {@link DagNode.DepFailurePolicy#RUN_WITH_ERROR} 的节点不被级联放弃：
     * 它就绪条件由 readyNodes 放宽为「上游全部到终态」，失败上游以 Failed 形态
     * 进入其结果视图。
     * </p>
     *
     * @param pending 待执行节点表（就地移除被放弃节点）
     * @param results 全量结果表（就地写入 Dropped）
     * @param nodes   全量节点
     */
    private <N extends DagNode> void cascadeDrop(Map<String, N> pending,
                                                 Map<String, DagNodeOutcome> results,
                                                 List<N> nodes) {
        boolean changed = true;
        while (changed) {
            changed = false;
            List<String> toDrop = new ArrayList<>();
            for (N node : pending.values()) {
                if (node.depFailurePolicy() == DagNode.DepFailurePolicy.RUN_WITH_ERROR) {
                    continue;
                }
                for (String dep : node.dependsOn()) {
                    DagNodeOutcome depOutcome = results.get(dep);
                    if (depOutcome instanceof DagNodeOutcome.Failed
                            || depOutcome instanceof DagNodeOutcome.Dropped) {
                        toDrop.add(node.nodeId());
                        break;
                    }
                }
            }
            for (String nodeId : toDrop) {
                N node = pending.remove(nodeId);
                if (node != null) {
                    changed = true;
                    List<String> failedDeps = node.dependsOn().stream()
                            .filter(dep -> !(results.get(dep) instanceof DagNodeOutcome.Done))
                            .toList();
                    results.put(nodeId, new DagNodeOutcome.Dropped("上游失败级联放弃: " + failedDeps));
                    log.info("DAG 节点级联放弃 node={} failedDeps={}", nodeId, failedDeps);
                }
            }
        }
    }

    /**
     * 计算本层就绪节点（全部依赖已完成；RUN_WITH_ERROR 节点放宽为依赖全部到终态）。
     *
     * @param pending 待执行节点表
     * @param results 全量结果表
     * @return 就绪节点列表
     */
    private <N extends DagNode> List<N> readyNodes(Map<String, N> pending,
                                                   Map<String, DagNodeOutcome> results) {
        List<N> ready = new ArrayList<>();
        for (N node : pending.values()) {
            boolean tolerate = node.depFailurePolicy() == DagNode.DepFailurePolicy.RUN_WITH_ERROR;
            boolean nodeReady = node.dependsOn().stream()
                    .allMatch(dep -> {
                        DagNodeOutcome depOutcome = results.get(dep);
                        if (depOutcome instanceof DagNodeOutcome.Done) {
                            return true;
                        }
                        // 容忍策略下，上游到达终态（失败/被放弃）即满足就绪
                        return tolerate && depOutcome != null;
                    });
            if (nodeReady) {
                ready.add(node);
            }
        }
        return ready;
    }

    /**
     * 图校验：重复节点 ID、缺失依赖、环检测（Kahn 计数）。
     *
     * @param nodes       全量节点
     * @param restoredIds 已有历史结果的节点 ID（恢复场景，依赖可指向已完成节点）
     * @return 校验错误说明；合法返回 null
     */
    private <N extends DagNode> String validate(List<N> nodes, Set<String> restoredIds) {
        // 恢复场景：节点可能已有历史结果——与 restoredIds 重合不算重复，只查列表内部重复
        Set<String> seen = new HashSet<>();
        Set<String> ids = new HashSet<>(restoredIds);
        for (N node : nodes) {
            if (!seen.add(node.nodeId())) {
                return "重复节点 ID: " + node.nodeId();
            }
            ids.add(node.nodeId());
        }
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (N node : nodes) {
            for (String dep : node.dependsOn()) {
                if (!ids.contains(dep)) {
                    return "节点 " + node.nodeId() + " 依赖了不存在的节点: " + dep;
                }
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.nodeId());
                indegree.merge(node.nodeId(), 1, Integer::sum);
            }
        }
        // Kahn：无环则全部节点可入队消减完毕
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (N node : nodes) {
            if (!indegree.containsKey(node.nodeId())) {
                queue.add(node.nodeId());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String current = queue.poll();
            visited++;
            for (String dependent : dependents.getOrDefault(current, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) {
                    queue.add(dependent);
                }
            }
        }
        return visited == nodes.size() ? null : "依赖图存在环（" + (nodes.size() - visited) + " 个节点不可达）";
    }

    /**
     * 执行预算熔断。
     *
     * @param ctx         运行上下文
     * @param results     已得结果
     * @param executions  执行计数
     * @param droppedHint 放弃数提示
     * @return 熔断结果
     */
    private DagOutcome fuseBudget(AgentRunContext ctx, Map<String, DagNodeOutcome> results,
                                  int executions, int droppedHint) {
        RuntimeErrorCode code = RuntimeErrorCode.DAG_EXECUTIONS_EXCEEDED;
        log.warn("DAG 执行预算熔断 runId={} executions={}", ctx.getRunId(), executions);
        Events.systemTrace(ctx.getBus(), "dag.executions_fuse", "DAG 节点执行次数超限",
                Map.of("executions", executions));
        DagAttributes.stashResults(ctx, results);
        DagAttributes.stashExecutions(ctx, executions);
        return DagOutcome.fused(code.code(), code.format(executions),
                results, executions, droppedHint);
    }

    /**
     * 统计被放弃节点数。
     *
     * @param results 全量结果表
     * @return 放弃数
     */
    private int droppedCount(Map<String, DagNodeOutcome> results) {
        return (int) results.values().stream()
                .filter(o -> o instanceof DagNodeOutcome.Dropped).count();
    }

    /**
     * 节点状态文本（步骤事件 data 用）。
     *
     * @param outcome  节点结果（可空：挂起节点不落结果）
     * @param awaiting 本层挂起结果
     * @param node     节点
     * @return 状态文本
     */
    private String outcomeStatus(DagNodeOutcome outcome, DagNodeOutcome.Awaiting awaiting,
                                 DagNode node) {
        if (outcome != null) {
            return outcome.getClass().getSimpleName().toUpperCase();
        }
        return awaiting != null ? "AWAITING" : "UNKNOWN";
    }

    /**
     * 取消检查点。
     *
     * @param ctx   运行上下文
     * @param phase 检查点标识
     * @throws RunCancellationException 已取消
     */
    private void throwIfCancelled(AgentRunContext ctx, String phase) {
        if (ctx.isCancelled()) {
            throw new RunCancellationException(ctx.getRunId(), phase);
        }
    }

    /**
     * 单层执行报告。
     *
     * @param awaiting   本层挂起结果（可空）
     * @param executions 累计执行次数（含本层）
     */
    private record LevelRun(DagNodeOutcome.Awaiting awaiting, int executions) {
    }
}
