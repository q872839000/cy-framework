// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Agent 事件类型（Agent 事件协议 v1 的核心判别量）
 * <p>
 * 事件类型显式枚举化,并通过 {@link JsonValue} 固化稳定 wire 名（如 {@code "StageChanged"}）,与枚举常量名解耦——
 * 对外协议契约不得耦合到 Java 实现细节上,重命名枚举常量 / 负载类不改变 wire 协议。
 * </p>
 * <p>
 * 每个类型自带四项协议元数据,作为<b>单一事实源</b>被信封工厂、可见性策略、反序列化器共用:
 * <ul>
 *     <li>{@link #channel()} —— 归属频道,由类型唯一决定,避免发射点各写各的频道；</li>
 *     <li>{@link #minLevel()} —— 下发所需的最低调试级别(用户频道事件为 {@link DebugLevel#OFF},即任何级别都下发)；</li>
 *     <li>{@link #terminal()} —— 是否为运行终态({@link #FINAL} / {@link #CANCELLED} / {@link #RUN_FAILED});
 *         终态如何区分(成功 / 取消 / 失败)直接看 {@code type} 本身,不再另设 outcome 字段；</li>
 *     <li>{@link #payloadType()} —— 该类型对应的负载类,供 {@link AgentEventDeserializer} 把 {@code data} 段还原为强类型。</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
public enum EventType {

    // ===== 用户频道:业务语义事件 =====
    /** 处理阶段推进(确定性编排事件)。 */
    STAGE_CHANGED("StageChanged", Channel.USER, DebugLevel.OFF, false, EventPayload.StageChanged.class),
    /** 阶段进度。 */
    PROGRESS("Progress", Channel.USER, DebugLevel.OFF, false, EventPayload.Progress.class),
    /** 里程碑 / 软检查提示。 */
    MILESTONE("Milestone", Channel.USER, DebugLevel.OFF, false, EventPayload.Milestone.class),
    /** 澄清请求。 */
    CLARIFICATION("Clarification", Channel.USER, DebugLevel.OFF, false, EventPayload.Clarification.class),
    /** 待人工审批。 */
    APPROVAL_REQUIRED("ApprovalRequired", Channel.USER, DebugLevel.OFF, false, EventPayload.ApprovalRequired.class),
    /** 业务错误 / 风险提示(非终态)。 */
    BUSINESS_ERROR("BusinessError", Channel.USER, DebugLevel.OFF, false, EventPayload.BusinessError.class),

    // ===== 用户频道:终态事件(成功 / 取消 / 失败直接由 type 区分)=====
    /** 正常终态:一次运行的最终答复。 */
    FINAL("Final", Channel.USER, DebugLevel.OFF, true, EventPayload.Final.class),
    /** 取消终态:用户协作式中止。 */
    CANCELLED("Cancelled", Channel.USER, DebugLevel.OFF, true, EventPayload.Cancelled.class),
    /** 失败终态:异常 / 拒绝等收尾。 */
    RUN_FAILED("RunFailed", Channel.USER, DebugLevel.OFF, true, EventPayload.RunFailed.class),

    // ===== 用户频道:步骤与块生命周期(流式协议核心)=====
    /** 步骤开始。 */
    STEP_STARTED("StepStarted", Channel.USER, DebugLevel.OFF, false, EventPayload.StepStarted.class),
    /** 步骤结束(正文相位结算点)。 */
    STEP_FINISHED("StepFinished", Channel.USER, DebugLevel.OFF, false, EventPayload.StepFinished.class),
    /** 流式块开始。 */
    BLOCK_START("BlockStart", Channel.USER, DebugLevel.OFF, false, EventPayload.BlockStart.class),
    /** 流式块增量。 */
    BLOCK_DELTA("BlockDelta", Channel.USER, DebugLevel.OFF, false, EventPayload.BlockDelta.class),
    /** 流式块结束。 */
    BLOCK_END("BlockEnd", Channel.USER, DebugLevel.OFF, false, EventPayload.BlockEnd.class),

    // ===== 用户频道:保活 =====
    /** 心跳保活(长时间无业务事件时维持连接,防中间代理空闲断流)。 */
    HEARTBEAT("Heartbeat", Channel.USER, DebugLevel.OFF, false, EventPayload.Heartbeat.class),

    // ===== 用户频道:领域流式数据(领域扩展槽,任何 Agent 可复用)=====
    /** 通用领域流式数据(运行途中的领域数据,与终态 RunResult.ext 对称)。 */
    DOMAIN_DATA("DomainData", Channel.USER, DebugLevel.OFF, false, EventPayload.DomainData.class),

    // ===== 调试频道:诊断事件(按 minLevel 过滤)=====
    /** 工具调用开始(minimal 及以上)。 */
    TOOL_CALL_START("ToolCallStart", Channel.DEBUG, DebugLevel.MINIMAL, false, EventPayload.ToolCallStart.class),
    /** 工具调用结束(minimal 及以上)。 */
    TOOL_CALL_END("ToolCallEnd", Channel.DEBUG, DebugLevel.MINIMAL, false, EventPayload.ToolCallEnd.class),
    /** LLM 调用汇总(verbose 及以上)。 */
    LLM_CALL("LlmCall", Channel.DEBUG, DebugLevel.VERBOSE, false, EventPayload.LlmCall.class),
    /** 原始 chunk(仅 trace)。 */
    RAW_LLM_CHUNK("RawLlmChunk", Channel.DEBUG, DebugLevel.TRACE, false, EventPayload.RawLlmChunk.class),
    /** 系统追踪(仅 trace)。 */
    SYSTEM_TRACE("SystemTrace", Channel.DEBUG, DebugLevel.TRACE, false, EventPayload.SystemTrace.class),

    // ===== 前向兼容哨兵(不可发射)=====
    /**
     * 未知类型哨兵:消费者(尤其是旧版本)遇到本版本枚举里不存在的 wire 类型时,{@link #fromWire(String)}
     * 归一到此,而非静默丢失,让消费者能显式感知“出现了不认识的事件,可能需要升级”。负载用
     * {@link EventPayload.Unknown} 原样保留。<b>仅用于反序列化,不参与发射 / 工厂</b>;
     * {@link EventVisibility} 对其保守不下发,避免把未知事件误放行。
     */
    UNKNOWN("Unknown", Channel.USER, DebugLevel.OFF, false, EventPayload.Unknown.class);

    private final String wireName;
    private final Channel channel;
    private final DebugLevel minLevel;
    private final boolean terminal;
    private final Class<? extends EventPayload> payloadType;

    EventType(String wireName, Channel channel, DebugLevel minLevel, boolean terminal,
              Class<? extends EventPayload> payloadType) {
        this.wireName = wireName;
        this.channel = channel;
        this.minLevel = minLevel;
        this.terminal = terminal;
        this.payloadType = payloadType;
    }

    /**
     * 对外 wire 名(= SSE {@code event} 字段值 = 信封 {@code type} 字段值),与枚举常量名解耦。
     *
     * @return 稳定 wire 名,如 {@code "StageChanged"}
     */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** @return 本类型固定归属的频道。 */
    public Channel channel() {
        return channel;
    }

    /** @return 下发所需最低调试级别;用户频道事件为 {@link DebugLevel#OFF}(任何级别都下发)。 */
    public DebugLevel minLevel() {
        return minLevel;
    }

    /** @return 是否为运行终态事件:{@link #FINAL} / {@link #CANCELLED} / {@link #RUN_FAILED}。 */
    public boolean terminal() {
        return terminal;
    }

    /** @return 该类型对应的负载类,供反序列化把 {@code data} 段还原为强类型。 */
    public Class<? extends EventPayload> payloadType() {
        return payloadType;
    }

    /** wire 名(小写)→ 类型的索引;不含 {@link #UNKNOWN}(它是未命中时的归一目标,不可按名查得)。 */
    private static final Map<String, EventType> BY_WIRE;

    static {
        Map<String, EventType> m = new HashMap<>();
        for (EventType t : values()) {
            if (t != UNKNOWN) {
                m.put(t.wireName.toLowerCase(Locale.ROOT), t);
            }
        }
        BY_WIRE = Map.copyOf(m);
    }

    /**
     * 按 wire 名解析事件类型,大小写不敏感(与协议内其它枚举的宽松解析一致)。
     * <p>
     * {@code null} 入参返回 {@code null}(无类型);<b>非空但无法识别</b>的 wire 名归一到 {@link #UNKNOWN}
     * 而非 {@code null}——给前向兼容一个显式信号,而不是静默丢事件。
     * </p>
     *
     * @param wireName wire 名
     * @return 事件类型;null 入参返回 null,未识别返回 {@link #UNKNOWN}
     */
    @JsonCreator
    public static EventType fromWire(String wireName) {
        if (wireName == null) {
            return null;
        }
        EventType t = BY_WIRE.get(wireName.toLowerCase(Locale.ROOT));
        return t != null ? t : UNKNOWN;
    }
}
