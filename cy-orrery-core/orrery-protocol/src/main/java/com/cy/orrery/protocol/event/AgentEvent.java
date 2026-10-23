// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Agent 运行期事件信封（Agent 事件协议 v1）
 * <p>
 * 这是对外的统一 wire 结构,也是所有 Agent 开发对接的标准事件外壳。一条事件 = 公共元数据信封 +
 * 强类型业务负载（{@link EventPayload},整体放在 {@code data} 段）。设计目标:
 * <ol>
 *     <li><b>自描述</b>:{@code type / channel} 都在 JSON body 里,脱离 SSE 帧也能判别事件,支持非 SSE 通道消费；</li>
 *     <li><b>可演进</b>:协议版本为<b>规范级</b>概念(不随事件上线,见下);未知事件类型由消费者归一到
 *         {@link EventType#UNKNOWN} 安全兜底,实现向后兼容；</li>
 *     <li><b>可扩展</b>:公共字段只此一处,新增公共元数据只改信封,不动负载；领域差异收进负载的 {@code ext}；</li>
 *     <li><b>类型安全且可往返</b>:{@code type} 为 {@link EventType} 枚举,与 {@link EventPayload} 编译期绑定;
 *         反序列化由 {@link AgentEventDeserializer} 据 {@code type} 把 {@code data} 还原为强类型,信封可整体往返；</li>
 *     <li><b>可观测可路由</b>:{@code agentId} 标识发射方(多 Agent 汇聚必需),{@code traceId / spanId} 贯通分布式链路；</li>
 *     <li><b>终态一等化</b>:{@code terminal} 直接回答“是否结束”;成功 / 取消 / 失败由 {@code type}（Final/Cancelled/RunFailed）区分,无需另设字段；</li>
 *     <li><b>有序可续传</b>:{@code seq} 由发射端在发射时原子赋值,协议要求 seq 顺序 == 发射顺序。</li>
 * </ol>
 * </p>
 * <p>
 * wire 字段(顶层时间戳用短键 {@code ts},其余统一小驼峰;信封不含协议版本字段——版本为规范级概念):
 * <pre>{@code
 * {
 *   "type": "Final",        // 事件类型 wire 名（= SSE event 字段）
 *   "channel": "user",      // 频道
 *   "runId": "8a4f...",     // 运行 ID（取消 / 续传用）
 *   "agentId": "my-agent",  // 发射方 Agent 标识（多 Agent 汇聚时区分来源）
 *   "traceId": "1b2c...",   // 分布式追踪 ID（可由上游网关经 traceparent 透传）
 *   "spanId": "9f3a...",    // 本运行 span（W3C trace-context 兼容）
 *   "seq": 12,              // 单调递增序号（排序 / 去重 / 续传的权威依据）
 *   "ts": 1717400000000,    // 产生时间戳（毫秒,观测用;排序以 seq 为准）
 *   "terminal": true,       // 是否运行终态（成功/取消/失败看 type: Final/Cancelled/RunFailed）
 *   "data": { ... }         // 事件专属负载（见 EventPayload 各类型）
 * }
 * }</pre>
 * 可选元数据（{@code agentId / traceId / spanId}）以 {@code @JsonInclude(NON_NULL)} 缺省省略,
 * 不污染高频事件的 wire。业务代码不应手填公共字段:各 Agent 的运行时应提供统一的信封工厂,
 * 据 {@link EventPayload#type()} 自动回填 {@code type / channel / terminal},{@code seq} 在发射时赋值。
 * </p>
 * <p>
 * <b>版本策略</b>:协议版本是<b>规范级</b>概念（权威定义见本模块 {@code docs/agent-event-protocol-v1.md}）,
 * <b>不随事件上线</b>(信封无版本字段),由对接双方按规范版本约定。同一规范版本内只做<b>向后兼容</b>的增量演进
 * （新增事件类型 / 新增可选字段 / 在 {@code ext} 内扩展）——老消费者遇到不认识的类型会归一到
 * {@link EventType#UNKNOWN} 并保留原始负载({@link EventPayload.Unknown}),不应崩溃。<b>不兼容</b>变更
 * （删除 / 改名 / 改语义已有字段）另起规范版本。中继 / 转发应基于原始 JSON,不要反序列化为
 * {@link AgentEvent} 再转发,以免丢失未知类型的 wire 名。
 * </p>
 *
 * @param type            事件类型
 * @param channel         频道（由 {@link EventType#channel()} 决定）
 * @param runId           所属运行 ID
 * @param agentId         发射方 Agent 标识；可为 null（未配置时省略）
 * @param traceId         分布式追踪 ID；缺省取 runId
 * @param spanId          本运行 span 标识（W3C trace-context 兼容）
 * @param seq             单调递增序号；构造时为 0,由发射端在发射时赋值（wire 上恒 ≥1,0 为“未赋号”哨兵）
 * @param timestampMs     事件产生时间戳（毫秒,wire 字段 {@code ts}）
 * @param terminal        是否运行终态（{@link EventType#terminal()};成功/取消/失败由 type 区分）
 * @param data            事件专属负载
 * @author pcy
 * @date 2026/04/23
 */
@JsonPropertyOrder({AgentEvent.FIELD_TYPE, AgentEvent.FIELD_CHANNEL, AgentEvent.FIELD_RUN_ID,
        AgentEvent.FIELD_AGENT_ID, AgentEvent.FIELD_TRACE_ID, AgentEvent.FIELD_SPAN_ID,
        AgentEvent.FIELD_SEQ, AgentEvent.FIELD_TS, AgentEvent.FIELD_TERMINAL, AgentEvent.FIELD_DATA})
@JsonDeserialize(using = AgentEventDeserializer.class)
public record AgentEvent(
        @JsonProperty(FIELD_TYPE) EventType type,
        @JsonProperty(FIELD_CHANNEL) Channel channel,
        @JsonProperty(FIELD_RUN_ID) String runId,
        @JsonProperty(FIELD_AGENT_ID) @JsonInclude(JsonInclude.Include.NON_NULL) String agentId,
        @JsonProperty(FIELD_TRACE_ID) @JsonInclude(JsonInclude.Include.NON_NULL) String traceId,
        @JsonProperty(FIELD_SPAN_ID) @JsonInclude(JsonInclude.Include.NON_NULL) String spanId,
        @JsonProperty(FIELD_SEQ) long seq,
        @JsonProperty(FIELD_TS) long timestampMs,
        @JsonProperty(FIELD_TERMINAL) boolean terminal,
        @JsonProperty(FIELD_DATA) EventPayload data
) {

    // ===== wire 字段名常量（信封契约的单一事实源）=====
    // 字段名经 @JsonProperty 绑定序列化、@JsonPropertyOrder 排序、AgentEventDeserializer 按名读取,
    // 三方共用此处常量——重命名 record 组件不会改变 wire 协议(与枚举 wireName 的解耦原则一致)。
    // 以原始 JSON 方式消费 / 中继 / 落存储的实现应引用这些常量,不要手写字面量。

    /** 信封字段名:事件类型(= SSE event 帧值)。 */
    public static final String FIELD_TYPE = "type";
    /** 信封字段名:频道。 */
    public static final String FIELD_CHANNEL = "channel";
    /** 信封字段名:运行 ID。 */
    public static final String FIELD_RUN_ID = "runId";
    /** 信封字段名:发射方 Agent 标识(可选,缺省省略)。 */
    public static final String FIELD_AGENT_ID = "agentId";
    /** 信封字段名:分布式追踪 ID(可选,缺省省略)。 */
    public static final String FIELD_TRACE_ID = "traceId";
    /** 信封字段名:本运行 span(可选,缺省省略)。 */
    public static final String FIELD_SPAN_ID = "spanId";
    /** 信封字段名:单调递增序号。 */
    public static final String FIELD_SEQ = "seq";
    /** 信封字段名:产生时间戳(毫秒,协议规定的稳定短键)。 */
    public static final String FIELD_TS = "ts";
    /** 信封字段名:是否运行终态。 */
    public static final String FIELD_TERMINAL = "terminal";
    /** 信封字段名:事件专属负载段。 */
    public static final String FIELD_DATA = "data";

    /**
     * 返回一个仅 {@code seq} 不同的副本。由发射端在串行化发射时调用,
     * 保证 seq 的赋值顺序与事件进入下游的顺序一致(杜绝并发生产者下 seq 与发射错位导致的续传漏 / 重)。
     *
     * @param newSeq 新序号
     * @return 替换 seq 后的新信封
     */
    public AgentEvent withSeq(long newSeq) {
        return new AgentEvent(type, channel, runId, agentId, traceId, spanId,
                newSeq, timestampMs, terminal, data);
    }
}
