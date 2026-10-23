// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.observe;


import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.runner.AgentRunner;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Micrometer 观测埋点：把运行时事件流转为 {@code orrery.agent.*} 指标。
 * <p>
 * 作为全局监听器经 {@code RuntimeEventBusFactory#addGlobalListener} 注册，
 * 纯粹基于 {@link RuntimeEvent} 做指标采集，对引擎 / harness 零侵入。
 * tag 一律从事件 {@code data} 读取（读不到就不打该 tag，保证 tag 基数稳定）；
 * 数值读取经 {@link Number} 保护性强转（data 是开放结构，类型不做硬假设）；
 * 单个事件处理异常只告警不阻断运行。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class MicrometerRuntimeObserver implements Consumer<RuntimeEvent> {

    private static final Logger log = LoggerFactory.getLogger(MicrometerRuntimeObserver.class);

    /** 熔断类系统追踪 kind（计入 {@code orrery.agent.fuses}，tag kind）。 */
    private static final Set<String> FUSE_KINDS = Set.of(
            "agent.idle_fuse", "agent.degradation_fuse",
            "pipeline.transitions_fuse", "pipeline.rollbacks_fuse");

    /** 上下文压缩追踪 kind（计入 {@code orrery.agent.context.compactions}）。 */
    private static final String CONTEXT_COMPACTED_KIND = "context.compacted";

    private final MeterRegistry registry;

    /**
     * 构造观测埋点。
     *
     * @param registry 指标注册表
     */
    public MicrometerRuntimeObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 注册活跃运行 gauge（{@code orrery.agent.runs.active}）。
     * <p>
     * 计数源是 {@link AgentRunner} 自维护的 {@code AtomicLong}
     * （startContext / restoreContext +1、终态 -1）——挂起中的运行计入活跃，
     * 观测口径与运行生命周期一致；gauge 随宿主单例 Runner 存续（弱引用由
     * Spring 容器强持有兜底）。
     * </p>
     *
     * @param registry 指标注册表
     * @param runner   运行门面（活跃计数源）
     */
    public static void registerActiveRunsGauge(MeterRegistry registry, AgentRunner runner) {
        registry.gauge("orrery.agent.runs.active", runner,
                r -> Math.max(0d, (double) r.activeRuns()));
    }

    /**
     * 消费一条运行时事件并折算为指标（异常吞掉仅告警）。
     *
     * @param event 运行时事件
     */
    @Override
    public void accept(RuntimeEvent event) {
        try {
            switch (event.type()) {
                case RUN_STARTED -> registry.counter("orrery.agent.runs.started").increment();
                case RUN_COMPLETED -> onRunCompleted(event.data());
                case RUN_FAILED -> onRunFailed(event.data());
                case CANCELLED -> registry.counter("orrery.agent.runs.cancelled").increment();
                case TOOL_FINISHED -> onToolFinished(event.data());
                case SYSTEM_TRACE -> onSystemTrace(event.data());
                case APPROVAL_REQUIRED -> registry.counter("orrery.agent.approvals.required").increment();
                case CLARIFICATION -> registry.counter("orrery.agent.clarifications").increment();
                default -> {
                    // 其余事件类型不产生指标
                }
            }
        } catch (Exception e) {
            log.warn("观测埋点处理事件失败 type={} runId={}", event.type(), event.runId(), e);
        }
    }

    /**
     * 运行完成：完成计数 + 轮次 / 工具调用 / token 分布摘要。
     *
     * @param data 事件数据（telemetry：rounds / toolCalls / promptTokens / completionTokens）
     */
    private void onRunCompleted(Map<String, Object> data) {
        registry.counter("orrery.agent.runs.completed").increment();
        Double rounds = number(data, "rounds");
        if (rounds != null) {
            registry.summary("orrery.agent.run.rounds").record(rounds);
        }
        Double toolCalls = number(data, "toolCalls");
        if (toolCalls != null) {
            registry.summary("orrery.agent.run.tool.calls").record(toolCalls);
        }
        Double promptTokens = number(data, "promptTokens");
        if (promptTokens != null) {
            registry.summary("orrery.agent.run.tokens", "kind", "prompt").record(promptTokens);
        }
        Double completionTokens = number(data, "completionTokens");
        if (completionTokens != null) {
            registry.summary("orrery.agent.run.tokens", "kind", "completion").record(completionTokens);
        }
    }

    /**
     * 运行失败：失败计数（tag code，读不到则不打）。
     *
     * @param data 事件数据
     */
    private void onRunFailed(Map<String, Object> data) {
        List<String> tags = tags("code", str(data, "code"));
        registry.counter("orrery.agent.runs.failed", tags.toArray(String[]::new)).increment();
    }

    /**
     * 工具调用结束：耗时 timer + 调用计数（tag tool / success，失败附加 tag error）。
     *
     * @param data 事件数据（toolName / durationMs / success / errorCode）
     */
    private void onToolFinished(Map<String, Object> data) {
        List<String> tags = new ArrayList<>(6);
        addTag(tags, "tool", str(data, "toolName"));
        addTag(tags, "success", str(data, "success"));
        String errorCode = str(data, "errorCode");
        if (errorCode != null && !errorCode.isEmpty()) {
            addTag(tags, "error", errorCode);
        }
        Double durationMs = number(data, "durationMs");
        if (durationMs != null) {
            registry.timer("orrery.agent.tool.duration", tags.toArray(String[]::new))
                    .record(durationMs.longValue(), TimeUnit.MILLISECONDS);
        }
        registry.counter("orrery.agent.tool.calls", tags.toArray(String[]::new)).increment();
    }

    /**
     * 系统追踪：熔断类 kind 计 {@code orrery.agent.fuses}（tag kind），
     * 上下文压缩计 {@code orrery.agent.context.compactions}。
     *
     * @param data 事件数据（kind / message）
     */
    private void onSystemTrace(Map<String, Object> data) {
        String kind = str(data, "kind");
        if (kind == null) {
            return;
        }
        if (FUSE_KINDS.contains(kind)) {
            registry.counter("orrery.agent.fuses", "kind", kind).increment();
        } else if (CONTEXT_COMPACTED_KIND.equals(kind)) {
            registry.counter("orrery.agent.context.compactions").increment();
        }
    }

    /**
     * 读字符串型 data 值（只接受字符序列 / 布尔 / 数值，其余视为读不到）。
     *
     * @param data 事件数据（可空）
     * @param key  键
     * @return 字符串值；读不到为 null
     */
    private static String str(Map<String, Object> data, String key) {
        if (data == null) {
            return null;
        }
        Object value = data.get(key);
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        return null;
    }

    /**
     * 读数值型 data 值（Number 保护强转，非数值视为读不到）。
     *
     * @param data 事件数据（可空）
     * @param key  键
     * @return 数值；读不到为 null
     */
    private static Double number(Map<String, Object> data, String key) {
        if (data == null) {
            return null;
        }
        return data.get(key) instanceof Number num ? num.doubleValue() : null;
    }

    /**
     * 组装单键 tag 列表（值为 null 时返回空列表，即不打该 tag）。
     *
     * @param key   tag 键
     * @param value tag 值（可空）
     * @return tag 键值对列表
     */
    private static List<String> tags(String key, String value) {
        return value == null ? List.of() : List.of(key, value);
    }

    /**
     * 追加一个 tag（值为 null 时跳过）。
     *
     * @param tags  tag 列表
     * @param key   tag 键
     * @param value tag 值（可空）
     */
    private static void addTag(List<String> tags, String key, String value) {
        if (value != null) {
            tags.add(key);
            tags.add(value);
        }
    }
}
