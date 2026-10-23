// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.dag;

import com.cy.orrery.agent.runtime.context.AgentRunContext;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAG 引擎单元测试。
 *
 * @author pcy
 */
class DagEngineTest {

    private final DagEngine engine = new DagEngine();

    /**
     * 构造运行上下文（内存总线）。
     *
     * @return 运行上下文
     */
    private static AgentRunContext newContext() {
        return new AgentRunContext("run-1", new InMemoryRuntimeEventBus("run-1"), null, null);
    }

    /**
     * 全部成功的处理器（记录调用次数）。
     *
     * @param calls 调用计数表
     * @return 处理器
     */
    private static DagNodeHandler<DagNode> okHandler(Map<String, AtomicInteger> calls) {
        return (ctx, node, deps) -> {
            calls.computeIfAbsent(node.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            return DagNodeOutcome.Done.of("evidence-" + node.nodeId());
        };
    }

    /**
     * 无依赖三节点并行到完成。
     */
    @Test
    void shouldRunIndependentNodesToDone() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        List<DagNode> nodes = List.of(
                DagNode.of("N1", "一"), DagNode.of("N2", "二"), DagNode.of("N3", "三"));

        DagOutcome outcome = engine.run(newContext(), nodes, okHandler(calls), DagConfig.defaults());

        assertEquals(DagOutcome.Status.DONE, outcome.status());
        assertEquals(3, outcome.executedCount());
        assertEquals(1, calls.get("N1").get());
        assertEquals(1, calls.get("N2").get());
        assertEquals(1, calls.get("N3").get());
    }

    /**
     * 依赖顺序：B 依赖 A，A 完成后 B 才执行且能看到 A 的结果。
     */
    @Test
    void shouldRespectDependencyOrder() {
        List<String> seen = new ArrayList<>();
        List<DagNode> nodes = List.of(
                DagNode.of("A", "上游"),
                new DagNode("B", "下游", List.of("A")));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            if (node.nodeId().equals("B")) {
                assertInstanceOf(DagNodeOutcome.Done.class, deps.get("A"));
                seen.add("B-saw-A");
            }
            return DagNodeOutcome.Done.of("ok");
        };

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertEquals(DagOutcome.Status.DONE, outcome.status());
        assertEquals(List.of("B-saw-A"), seen);
    }

    /**
     * 同层并行真实性：两个节点各自等待 latch，互不阻塞即并行。
     */
    @Test
    void shouldRunSameLevelNodesInParallel() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            bothStarted.countDown();
            try {
                // 若串行执行，先到者永远等不到后到者而超时
                assertTrue(bothStarted.await(5, TimeUnit.SECONDS), "同层节点未并行启动");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DagNodeOutcome.Done.of("ok");
        };
        List<DagNode> nodes = List.of(DagNode.of("P1", "并一"), DagNode.of("P2", "并二"));

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertEquals(DagOutcome.Status.DONE, outcome.status());
    }

    /**
     * 缺失依赖：图校验失败，不执行任何节点。
     */
    @Test
    void shouldFailOnMissingDependency() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        List<DagNode> nodes = List.of(new DagNode("B", "下游", List.of("GHOST")));

        DagOutcome outcome = engine.run(newContext(), nodes, okHandler(calls), DagConfig.defaults());

        assertEquals(DagOutcome.Status.FAILED, outcome.status());
        assertEquals("DAG_GRAPH_INVALID", outcome.failedCode());
        assertTrue(calls.isEmpty());
    }

    /**
     * 环检测：A→B→A 不可执行。
     */
    @Test
    void shouldFailOnCycle() {
        List<DagNode> nodes = List.of(
                new DagNode("A", "甲", List.of("B")),
                new DagNode("B", "乙", List.of("A")));

        DagOutcome outcome = engine.run(newContext(), nodes, okHandler(new ConcurrentHashMap<>()),
                DagConfig.defaults());

        assertEquals(DagOutcome.Status.FAILED, outcome.status());
        assertEquals("DAG_GRAPH_INVALID", outcome.failedCode());
    }

    /**
     * 失败级联：A 失败 → 依赖 A 的 B 被放弃；独立分支 C 照常完成 → PARTIAL。
     */
    @Test
    void shouldCascadeDropDependentsOnFailure() {
        List<DagNode> nodes = List.of(
                DagNode.of("A", "会失败"),
                new DagNode("B", "依赖A", List.of("A")),
                DagNode.of("C", "独立分支"));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> node.nodeId().equals("A")
                ? DagNodeOutcome.Failed.of("BIZ_FAIL", "上游失败", true)
                : DagNodeOutcome.Done.of("ok");

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertEquals(DagOutcome.Status.PARTIAL, outcome.status());
        assertInstanceOf(DagNodeOutcome.Failed.class, outcome.results().get("A"));
        assertInstanceOf(DagNodeOutcome.Dropped.class, outcome.results().get("B"));
        assertInstanceOf(DagNodeOutcome.Done.class, outcome.results().get("C"));
        assertEquals(1, outcome.droppedCount());
    }

    /**
     * 挂起与恢复：节点首次返回 Awaiting → 整体 AWAITING；
     * 重入后已完成节点不重跑，挂起节点恢复执行。
     */
    @Test
    void shouldSuspendOnAwaitingAndResumeWithoutReRun() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        List<DagNode> nodes = List.of(
                DagNode.of("FIRST", "先完成"),
                new DagNode("GATE", "人工门", List.of("FIRST")),
                new DagNode("LAST", "收尾", List.of("GATE")));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            calls.computeIfAbsent(node.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            if (node.nodeId().equals("GATE") && calls.get("GATE").get() == 1) {
                return DagNodeOutcome.Awaiting.of("approval-1", "等待人工确认");
            }
            return DagNodeOutcome.Done.of("ok");
        };
        AgentRunContext ctx = newContext();

        DagOutcome first = engine.run(ctx, nodes, handler, DagConfig.defaults());
        assertEquals(DagOutcome.Status.AWAITING, first.status());
        assertEquals("approval-1", first.awaitingKey());
        assertEquals(1, calls.get("FIRST").get());
        assertEquals(0, calls.getOrDefault("LAST", new AtomicInteger()).get());

        // 恢复重入（真实链路经检查点往返，这里同上下文模拟属性恢复）
        DagOutcome second = engine.run(ctx, nodes, handler, DagConfig.defaults());
        assertEquals(DagOutcome.Status.DONE, second.status());
        assertEquals(1, calls.get("FIRST").get(), "已完成节点不得重跑");
        assertEquals(2, calls.get("GATE").get(), "挂起节点恢复后应重入处理器");
        assertEquals(1, calls.get("LAST").get());
    }

    /**
     * 失败容忍：聚合节点声明 RUN_WITH_ERROR 后，上游失败不级联放弃，
     * 失败结果以 Failed 形态进入依赖视图。
     */
    @Test
    void shouldRunTolerantNodeWithFailedDependency() {
        List<DagNode> nodes = List.of(
                DagNode.of("A", "会失败"),
                new DagNode("AGG", "聚合", List.of("A"),
                        DagNode.DepFailurePolicy.RUN_WITH_ERROR));
        Map<String, DagNodeOutcome> seenByAgg = new ConcurrentHashMap<>();
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            if (node.nodeId().equals("A")) {
                return DagNodeOutcome.Failed.of("BIZ_FAIL", "上游失败", true);
            }
            seenByAgg.putAll(deps);
            return DagNodeOutcome.Done.of("聚合完成");
        };

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertEquals(DagOutcome.Status.PARTIAL, outcome.status());
        assertInstanceOf(DagNodeOutcome.Failed.class, seenByAgg.get("A"),
                "容忍节点的依赖视图应携带失败上游的 Failed 结果");
        assertInstanceOf(DagNodeOutcome.Done.class, outcome.results().get("AGG"));
        assertEquals(0, outcome.droppedCount());
    }

    /**
     * 默认策略不受影响：未声明容忍的下游仍被级联放弃。
     */
    @Test
    void shouldStillDropIntolerantDependents() {
        List<DagNode> nodes = List.of(
                DagNode.of("A", "会失败"),
                new DagNode("AGG", "聚合", List.of("A"), DagNode.DepFailurePolicy.RUN_WITH_ERROR),
                new DagNode("STRICT", "严格下游", List.of("A")));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> node.nodeId().equals("A")
                ? DagNodeOutcome.Failed.of("BIZ_FAIL", "上游失败", true)
                : DagNodeOutcome.Done.of("ok");

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertInstanceOf(DagNodeOutcome.Done.class, outcome.results().get("AGG"));
        assertInstanceOf(DagNodeOutcome.Dropped.class, outcome.results().get("STRICT"));
    }

    /**
     * 执行预算熔断：挂起-恢复循环导致累计执行次数触顶，层调度前熔断。
     */
    @Test
    void shouldFuseWhenExecutionsExceedBudget() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        List<DagNode> nodes = List.of(
                DagNode.of("FIRST", "先完成"),
                new DagNode("GATE", "人工门", List.of("FIRST")),
                new DagNode("LAST", "收尾", List.of("GATE")));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            calls.computeIfAbsent(node.nodeId(), k -> new AtomicInteger()).incrementAndGet();
            if (node.nodeId().equals("GATE") && calls.get("GATE").get() == 1) {
                return DagNodeOutcome.Awaiting.of("approval-1", "等待人工确认");
            }
            return DagNodeOutcome.Done.of("ok");
        };
        AgentRunContext ctx = newContext();
        DagConfig config = DagConfig.of(4, 0L, 2);

        DagOutcome first = engine.run(ctx, nodes, handler, config);
        assertEquals(DagOutcome.Status.AWAITING, first.status());

        // 恢复重入：累计执行已达预算（FIRST + GATE = 2），仍有工作 → 熔断
        DagOutcome second = engine.run(ctx, nodes, handler, config);
        assertEquals(DagOutcome.Status.FUSED, second.status());
        assertEquals("DAG_EXECUTIONS_EXCEEDED", second.failedCode());
        assertEquals(1, calls.get("GATE").get(), "熔断应先于挂起节点重跑");
    }

    /**
     * 单节点超时：超时节点按可重试失败收敛，不拖死整体。
     */
    @Test
    void shouldTimeoutSlowNode() {
        DagConfig config = DagConfig.of(2, 200L, 100);
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            if (node.nodeId().equals("SLOW")) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return DagNodeOutcome.Done.of("ok");
        };
        List<DagNode> nodes = List.of(DagNode.of("SLOW", "慢节点"), DagNode.of("FAST", "快节点"));

        DagOutcome outcome = engine.run(newContext(), nodes, handler, config);

        assertEquals(DagOutcome.Status.PARTIAL, outcome.status());
        DagNodeOutcome slow = outcome.results().get("SLOW");
        assertInstanceOf(DagNodeOutcome.Failed.class, slow);
        assertEquals("DAG_NODE_TIMEOUT", ((DagNodeOutcome.Failed) slow).code());
    }

    /**
     * 同层双挂起：两个 Awaiting 都不落 results（终态不变量），取首个为挂起键；
     * 恢复后两个节点都重跑。
     */
    @Test
    void shouldKeepBothAwaitingNodesOutOfResults() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        List<DagNode> nodes = List.of(
                DagNode.of("G1", "门一"), DagNode.of("G2", "门二"),
                new DagNode("END", "收尾", List.of("G1", "G2")));
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            int count = calls.computeIfAbsent(node.nodeId(), k -> new AtomicInteger())
                    .incrementAndGet();
            if (count == 1 && (node.nodeId().equals("G1") || node.nodeId().equals("G2"))) {
                return DagNodeOutcome.Awaiting.of("gate-" + node.nodeId(), "等待确认");
            }
            return DagNodeOutcome.Done.of("ok");
        };
        AgentRunContext ctx = newContext();

        DagOutcome first = engine.run(ctx, nodes, handler, DagConfig.defaults());
        assertEquals(DagOutcome.Status.AWAITING, first.status());
        assertTrue(first.results().values().stream()
                .noneMatch(o -> o instanceof DagNodeOutcome.Awaiting), "挂起不得落入结果集");
        assertTrue(first.results().values().stream()
                .noneMatch(o -> o instanceof DagNodeOutcome.Dropped), "挂起不得物化为放弃");

        // 两次重入：每次放行一个门（引擎每轮只取一个挂起键，语义为逐门放行）
        engine.run(ctx, nodes, handler, DagConfig.defaults());
        DagOutcome third = engine.run(ctx, nodes, handler, DagConfig.defaults());
        assertEquals(DagOutcome.Status.DONE, third.status());
        assertEquals(2, calls.get("G1").get());
        assertEquals(2, calls.get("G2").get());
        assertEquals(1, calls.get("END").get());
    }

    /**
     * 容忍节点的上游为 Dropped（传递性失败物化）时仍可执行，且视图中看到失败链。
     */
    @Test
    void shouldRunTolerantNodeWithDroppedDependency() {
        List<DagNode> nodes = List.of(
                DagNode.of("A", "会失败"),
                new DagNode("B", "严格下游被级联", List.of("A")),
                new DagNode("AGG", "容忍聚合", List.of("A", "B"), DagNode.DepFailurePolicy.RUN_WITH_ERROR));
        Map<String, DagNodeOutcome> seenByAgg = new ConcurrentHashMap<>();
        DagNodeHandler<DagNode> handler = (ctx, node, deps) -> {
            if (node.nodeId().equals("A")) {
                return DagNodeOutcome.Failed.of("BIZ_FAIL", "上游失败", true);
            }
            if (node.nodeId().equals("AGG")) {
                seenByAgg.putAll(deps);
            }
            return DagNodeOutcome.Done.of("ok");
        };

        DagOutcome outcome = engine.run(newContext(), nodes, handler, DagConfig.defaults());

        assertEquals(DagOutcome.Status.PARTIAL, outcome.status());
        assertInstanceOf(DagNodeOutcome.Dropped.class, outcome.results().get("B"));
        assertInstanceOf(DagNodeOutcome.Done.class, outcome.results().get("AGG"),
                "容忍节点须在双上游（失败+被放弃）到终态后执行");
        assertInstanceOf(DagNodeOutcome.Failed.class, seenByAgg.get("A"));
        assertInstanceOf(DagNodeOutcome.Dropped.class, seenByAgg.get("B"));
    }
}
