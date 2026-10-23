// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.bridge.protocol;


import com.cy.orrery.agent.runtime.event.RuntimeEvent;
import com.cy.orrery.agent.runtime.event.impl.InMemoryRuntimeEventBus;
import com.cy.orrery.protocol.event.AgentEvent;
import com.cy.orrery.protocol.event.BlockType;
import com.cy.orrery.protocol.event.EventPayload;
import com.cy.orrery.protocol.event.EventType;
import com.cy.orrery.protocol.event.Phase;
import com.cy.orrery.protocol.event.RunResult;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运行时事件 → Agent 事件协议 v1 的线协议桥。
 * <p>
 * 作为监听器挂在运行时事件总线上（{@code bus.addListener(bridge)}），把领域无关的
 * {@link RuntimeEvent} 逐个映射为协议信封 {@link AgentEvent} 并经 {@link AgentEventPublisher} 下发。
 * 信封约定：
 * <ul>
 *     <li>{@code type / channel / terminal} 由负载反推（{@link EventPayload#type()} 单一事实源）——
 *         运行时终态三件套（RUN_COMPLETED / RUN_FAILED / CANCELLED）分别落到
 *         Final / RunFailed / Cancelled，与协议 {@link EventType#terminal()} 一致；</li>
 *     <li>{@code seq} 由本桥赋值（桥是协议发射端，协议规定发射端赋值序）：per-runId
 *         计数器逐事件递增、初值不低于运行时侧 seq——一条运行时事件合成的多条协议
 *         事件（BlockStart + BlockDelta / BlockEnd 批量闭合）seq 严格递增互不相同，
 *         满足协议「seq 顺序 == 发射顺序」；</li>
 *     <li>{@code traceId} 缺省取 runId（协议缺省约定），{@code spanId} 置 null（未接入分布式追踪）；</li>
 *     <li>{@code agentId} 取构造传入值（可为 null，wire 上省略）。</li>
 * </ul>
 * </p>
 * <p>
 * 流式块合成：运行时只有扁平的 TEXT_DELTA 增量，协议的块生命周期（BlockStart / BlockDelta /
 * BlockEnd）由本桥合成——同一 stepIndex 同 kind 的首个增量到达前先补发 BlockStart，
 * STEP_FINISHED 到达时先闭合该步骤全部未闭合块（发 BlockEnd）再发 StepFinished。
 * 块 ID 规则：{@code "s{stepIndex}-{kind}"}；kind=reasoning 映射 {@link BlockType#REASONING}，
 * 其余（含缺省 text）映射 {@link BlockType#TEXT}。
 * </p>
 * <p>
 * 前向兼容：运行时枚举新增而本桥未覆盖的类型落入 default 分支，映射为
 * {@link EventPayload.DomainData}（kind={@code "runtime." + 类型名小写}，ext 为原始 data），
 * 保证事件不丢失、不下线。
 * </p>
 * <p>
 * 实例与线程约定：桥可注册为全局监听器服务并发多 run——<b>块状态按 runId 隔离</b>，
 * 并发 run 同步骤号的块 ID 互不串扰（终态事件只闭合并清理本 run 的块）。
 * 事件总线为同步单线程派发，各 run 的块状态表使用普通 {@link LinkedHashMap}
 * （保持开启顺序，使 BlockEnd 闭合顺序确定），无需并发结构。
 * 故障隔离与 {@code InMemoryRuntimeEventBus} 的监听器语义一致：发布异常仅记录 error 日志，
 * 不向引擎主流程传播。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class AgentEventBridge implements Consumer<RuntimeEvent> {

    private static final Logger log = LoggerFactory.getLogger(AgentEventBridge.class);

    private final AgentEventPublisher publisher;
    private final String agentId;

    /**
     * 未闭合流式块状态（runId → blockId → 块状态；仅总线派发线程访问内层表，
     * 外层表被注册器/终态清理跨线程触达，用 ConcurrentHashMap）。
     */
    private final Map<String, Map<String, BlockState>> openBlocksByRun
            = new ConcurrentHashMap<>();

    /** per-runId 协议 seq 计数器（每条合成事件取新序；终态后条目清理防泄漏）。 */
    private final Map<String, AtomicLong> seqByRun = new ConcurrentHashMap<>();

    /**
     * 构造线协议桥。
     *
     * @param publisher 协议事件下发端口
     * @param agentId   发射方 Agent 标识（可为 null，wire 上省略）
     */
    public AgentEventBridge(AgentEventPublisher publisher, String agentId) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.agentId = agentId;
    }

    /**
     * 消费一条运行时事件：映射为协议事件并下发（异常隔离，不阻断引擎）。
     *
     * @param event 运行时事件
     */
    @Override
    public void accept(RuntimeEvent event) {
        try {
            mapAndPublish(event);
        } catch (Exception e) {
            // 桥接故障隔离：发布 / 映射异常不阻断引擎主流程，与总线监听器隔离语义一致
            log.error("AgentEvent 桥接发布失败 runId={} seq={} type={}",
                    event.runId(), event.seq(), event.type(), e);
        }
    }

    /**
     * 按运行时事件类型映射并发布（按语义分组分发，各组复杂度独立受控）。
     *
     * @param event 运行时事件
     */
    private void mapAndPublish(RuntimeEvent event) {
        switch (event.type()) {
            case RUN_STARTED, RUN_COMPLETED, RUN_FAILED, CANCELLED -> mapLifecycle(event);
            case STEP_STARTED, STEP_FINISHED, TOOL_STARTED, TOOL_FINISHED, TEXT_DELTA ->
                    mapStepAndTool(event);
            case MILESTONE, SYSTEM_TRACE, CLARIFICATION, APPROVAL_REQUIRED, BUSINESS_ERROR ->
                    mapBusiness(event);
            case LLM_CALL -> mapLlmCall(event);
            default -> publish(event, toDomainData(event));
        }
    }

    /**
     * LLM 调用遥测事件映射（调试频道）。
     *
     * @param event 运行时事件
     */
    private void mapLlmCall(RuntimeEvent event) {
        Map<String, Object> data = event.data();
        publish(event, new EventPayload.LlmCall(
                asString(data.get("callId")),
                asString(data.get("model")),
                asInt(data.get("promptTokens"), 0),
                asInt(data.get("completionTokens"), 0),
                asLong(data.get("latencyMs"), 0L),
                asString(data.get("finishReason"))));
    }

    /**
     * 生命周期事件映射（运行开始 / 完成 / 失败 / 取消）。
     *
     * @param event 运行时事件
     */
    private void mapLifecycle(RuntimeEvent event) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case RUN_STARTED -> publish(event,
                    new EventPayload.SystemTrace("agent.start", "运行开始", null));
            case RUN_COMPLETED -> {
                // 终态先闭合全部未闭合流式块：消费方在终态后不再收事件，
                // 悬空 BlockStart（有开无关）会让块渲染挂死
                closeAllBlocks(event);
                publish(event, new EventPayload.Final(new RunResult(
                        RunResult.STOP_COMPLETED, asInt(data.get("rounds"), 0),
                        asInt(data.get("toolCalls"), 0), null, null)));
            }
            case RUN_FAILED -> {
                closeAllBlocks(event);
                String code = asString(data.get("code"));
                publish(event, new EventPayload.RunFailed(code, asString(data.get("message")),
                        asBoolean(data.get("retryable"), false), RunResult.ofStop(code)));
            }
            case CANCELLED -> {
                closeAllBlocks(event);
                publish(event, new EventPayload.Cancelled(
                        asString(data.get("reason")), asString(data.get("phase")), null));
            }
            default -> {
                // 分组分发保证不可达
            }
        }
    }

    /**
     * 步骤与工具事件映射（含流式块生命周期合成）。
     *
     * @param event 运行时事件
     */
    private void mapStepAndTool(RuntimeEvent event) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case STEP_STARTED -> {
                // DAG 节点级 step（data.node 非空）不是 LLM 推理轮：转译为调试追踪，
                // 避免消费方误读为"第 N 轮推理"；DAG 节点无流式块，无需块闭合结算
                Object node = data.get("node");
                if (node != null) {
                    publish(event, new EventPayload.SystemTrace("dag.node",
                            "节点 " + node + " 开始执行", null));
                } else {
                    publish(event, new EventPayload.StepStarted(asInt(data.get("stepIndex"), 0)));
                }
            }
            case STEP_FINISHED -> {
                Object node = data.get("node");
                if (node != null) {
                    publish(event, new EventPayload.SystemTrace("dag.node",
                            "节点 " + node + " 结束 · " + asString(data.get("status")), null));
                } else {
                    mapStepFinished(event, data);
                }
            }
            case TOOL_STARTED -> publish(event, new EventPayload.ToolCallStart(
                    asString(data.get("toolCallId")), asString(data.get("toolName")), null, null));
            case TOOL_FINISHED -> publish(event, new EventPayload.ToolCallEnd(
                    asString(data.get("toolCallId")), asString(data.get("toolName")),
                    asBoolean(data.get("success"), false), null,
                    asLong(data.get("durationMs"), 0L), asString(data.get("errorCode"))));
            case TEXT_DELTA -> appendDelta(event, data);
            default -> {
                // 分组分发保证不可达
            }
        }
    }

    /**
     * 业务语义事件映射（里程碑 / 追踪 / 澄清 / 审批 / 业务错误）。
     *
     * @param event 运行时事件
     */
    private void mapBusiness(RuntimeEvent event) {
        Map<String, Object> data = event.data();
        switch (event.type()) {
            case MILESTONE -> publish(event,
                    new EventPayload.Milestone("general", asString(data.get("title")), null));
            case SYSTEM_TRACE -> publish(event, new EventPayload.SystemTrace(
                    asString(data.get("kind")), asString(data.get("message")), traceMeta(data)));
            case CLARIFICATION -> publish(event, new EventPayload.Clarification(
                    asString(data.get("question")), asStringList(data.get("options")),
                    EventPayload.Clarification.KIND_INFO));
            case APPROVAL_REQUIRED -> publish(event, new EventPayload.ApprovalRequired(
                    asString(data.get("approvalId")), asString(data.get("summary")),
                    approvalDiff(data)));
            case BUSINESS_ERROR -> publish(event, new EventPayload.BusinessError(
                    asString(data.get("code")), asString(data.get("message")),
                    asBoolean(data.get("retryable"), false)));
            default -> {
                // 分组分发保证不可达
            }
        }
    }

    /**
     * 步骤结束映射：先闭合该步骤全部未闭合块，再发 StepFinished（相位结算）。
     *
     * @param event 运行时事件
     * @param data  事件数据（stepIndex / hadToolCalls）
     */
    private void mapStepFinished(RuntimeEvent event, Map<String, Object> data) {
        int stepIndex = asInt(data.get("stepIndex"), 0);
        boolean hadToolCalls = asBoolean(data.get("hadToolCalls"), false);
        closeBlocks(event, stepIndex);
        publish(event, new EventPayload.StepFinished(stepIndex, hadToolCalls,
                hadToolCalls ? Phase.COMMENTARY : Phase.FINAL));
    }

    /**
     * 处理流式正文增量：首个增量前补发 BlockStart，随后发 BlockDelta。
     *
     * @param event 运行时事件
     * @param data  事件数据（stepIndex / delta / kind）
     */
    private void appendDelta(RuntimeEvent event, Map<String, Object> data) {
        int stepIndex = asInt(data.get("stepIndex"), 0);
        String kind = asString(data.get("kind"));
        String label = asString(data.get("label"));
        String blockId = "s" + stepIndex + "-" + (kind == null ? "text" : kind);
        BlockType blockType = "reasoning".equals(kind) ? BlockType.REASONING : BlockType.TEXT;
        Map<String, BlockState> openBlocks = openBlocks(event);
        BlockState state = openBlocks.get(blockId);
        if (state == null) {
            state = new BlockState(stepIndex, blockId, blockType);
            openBlocks.put(blockId, state);
            publish(event, new EventPayload.BlockStart(stepIndex, blockId, blockType, label));
        }
        publish(event, new EventPayload.BlockDelta(stepIndex, blockId, blockType,
                asString(data.get("delta"))));
    }

    /**
     * 闭合指定步骤的全部未闭合块（STEP_FINISHED 前置结算）。
     *
     * @param event     源运行时事件（信封元数据复用）
     * @param stepIndex 步骤序号
     */
    private void closeBlocks(RuntimeEvent event, int stepIndex) {
        Map<String, BlockState> openBlocks = openBlocksByRun.get(event.runId());
        if (openBlocks == null) {
            return;
        }
        Iterator<Map.Entry<String, BlockState>> iterator = openBlocks.entrySet().iterator();
        while (iterator.hasNext()) {
            BlockState state = iterator.next().getValue();
            if (state.stepIndex() == stepIndex) {
                publish(event, new EventPayload.BlockEnd(
                        state.stepIndex(), state.blockId(), state.blockType()));
                iterator.remove();
            }
        }
    }

    /**
     * 闭合全部未闭合块（终态事件前置结算：跨步骤扫尾，防悬空 BlockStart）。
     * 只处理本 run 的块并整体移除该 run 的状态表——绝不跨 run 扫尾。
     *
     * @param event 源运行时事件
     */
    private void closeAllBlocks(RuntimeEvent event) {
        Map<String, BlockState> openBlocks = openBlocksByRun.remove(event.runId());
        if (openBlocks == null) {
            return;
        }
        for (BlockState state : openBlocks.values()) {
            publish(event, new EventPayload.BlockEnd(
                    state.stepIndex(), state.blockId(), state.blockType()));
        }
    }

    /**
     * 取（或建）该 run 的未闭合块状态表。
     *
     * @param event 运行时事件（runId 来源）
     * @return 该 run 的块状态表（仅总线派发线程访问）
     */
    private Map<String, BlockState> openBlocks(RuntimeEvent event) {
        return openBlocksByRun.computeIfAbsent(event.runId(),
                runId -> new LinkedHashMap<>());
    }

    /**
     * 装配协议信封并下发。
     * <p>
     * seq 由本桥赋值（桥是协议发射端，协议规定发射端赋值）：per-runId 计数器逐事件递增，
     * 初值取首个源事件的运行时 seq——保证协议序不低于运行时侧序且严格单调
     * （一条 TEXT_DELTA 合成的 BlockStart + BlockDelta 两条事件 seq 互不相同、
     * 按发射顺序递增）。终态事件发射后清理该 run 的计数器条目（终态即运行的最后一跳）。
     * </p>
     *
     * @param source  源运行时事件（runId / 时间戳来源）
     * @param payload 协议负载
     */
    private void publish(RuntimeEvent source, EventPayload payload) {
        EventType type = payload.type();
        long seq = nextSeq(source);
        publisher.publish(new AgentEvent(
                type, type.channel(), source.runId(), agentId,
                source.runId(), null, seq, source.timestampMillis(),
                type.terminal(), payload));
        if (type.terminal()) {
            seqByRun.remove(source.runId());
        }
    }

    /**
     * 取该运行的下一条协议 seq。
     *
     * @param source 源运行时事件
     * @return 单调递增的协议 seq
     */
    private long nextSeq(RuntimeEvent source) {
        AtomicLong counter = seqByRun.computeIfAbsent(source.runId(),
                runId -> new AtomicLong(source.seq()));
        return counter.incrementAndGet();
    }

    /**
     * 未知 / 新增运行时类型的前向兼容兜底：映射为领域扩展数据。
     *
     * @param event 运行时事件
     * @return DomainData 负载（kind=runtime.类型名小写，ext 为原始 data）
     */
    static EventPayload.DomainData toDomainData(RuntimeEvent event) {
        return new EventPayload.DomainData(
                "runtime." + event.type().name().toLowerCase(Locale.ROOT), event.data());
    }

    /**
     * 提取系统追踪的扩展元数据（剔除 kind / message 后的剩余 entries）。
     *
     * @param data 事件数据
     * @return 扩展元数据 Map；无扩展时为 null
     */
    private static Object traceMeta(Map<String, Object> data) {
        Map<String, Object> meta = null;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if ("kind".equals(entry.getKey()) || "message".equals(entry.getKey())) {
                continue;
            }
            if (meta == null) {
                meta = new LinkedHashMap<>();
            }
            meta.put(entry.getKey(), entry.getValue());
        }
        return meta;
    }

    /**
     * 组装审批预期变更负载。
     *
     * @param data 事件数据（callId / affectedKeys）
     * @return 变更描述 Map
     */
    private static Object approvalDiff(Map<String, Object> data) {
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("callId", data.get("callId"));
        diff.put("affectedKeys", asStringList(data.get("affectedKeys")));
        return diff;
    }

    /**
     * 安全取 int（非 Number 落默认值）。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return int 值
     */
    private static int asInt(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    /**
     * 安全取 long（非 Number 落默认值）。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return long 值
     */
    private static long asLong(Object value, long defaultValue) {
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    /**
     * 安全取 boolean（非 Boolean 落默认值）。
     *
     * @param value        原始值
     * @param defaultValue 默认值
     * @return boolean 值
     */
    private static boolean asBoolean(Object value, boolean defaultValue) {
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    /**
     * 安全取 String（非 String 返回 null）。
     *
     * @param value 原始值
     * @return 字符串；非 String 为 null
     */
    private static String asString(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * 安全取字符串列表（非 List 落空列表；非 String 元素过滤，
     * 与其它 as* 转换器的"落默认值"语义一致，不抛 ClassCastException）。
     *
     * @param value 原始值
     * @return 字符串列表
     */
    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance)
                    .map(String.class::cast).toList();
        }
        return List.of();
    }

    /**
     * 未闭合流式块状态。
     *
     * @param stepIndex 所属步骤序号
     * @param blockId   块唯一 ID
     * @param blockType 块类型
     */
    private record BlockState(int stepIndex, String blockId, BlockType blockType) {
    }
}
