// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * {@link AgentEvent} 的反序列化器（Agent 事件协议 v1）
 * <p>
 * 信封的判别量 {@code type} 在<b>顶层</b>、而强类型负载在 {@code data} 段——这种“判别量在 data 之外”的外置
 * 判别结构,标准 Jackson 多态(@JsonTypeInfo)处理起来既别扭又易踩坑,因此这里用显式反序列化器,把
 * {@link EventType} 复用为“{@code type} → 负载类”的<b>单一事实源</b>（见 {@link EventType#payloadType()}）:
 * <ol>
 *     <li>读取信封顶层 {@code type},还原为 {@link EventType}（未知类型 → {@link EventType#UNKNOWN}）；</li>
 *     <li>据 {@link EventType#payloadType()} 把 {@code data} 段反序列化为对应负载 record；未知类型用
 *         {@link EventPayload.Unknown} 原样保留字段,保证中继 / 跨版本场景下负载不丢失;</li>
 *     <li>回填其余公共字段,组装成 {@link AgentEvent}。</li>
 * </ol>
 * 与之配对:序列化走 record 默认机制(信封顶层平铺公共字段、{@code data} 平铺负载字段),由 {@link AgentEvent}
 * 上的 {@code @JsonDeserialize(using = ...)} 绑定本类完成往返。任何 {@link ObjectMapper} 无需注册 Module 即可
 * 正确反序列化 {@code AgentEvent},这是“别的 Agent / 服务能把 wire JSON 解析回强类型对象”的标准能力基石。
 * 字段名一律引用 {@link AgentEvent} 的 {@code FIELD_*} 常量,与序列化侧（{@code @JsonProperty}）共用单一事实源。
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
public final class AgentEventDeserializer extends JsonDeserializer<AgentEvent> {

    @Override
    public AgentEvent deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode node = mapper.readTree(p);

        EventType type = EventType.fromWire(text(node, AgentEvent.FIELD_TYPE));
        Channel channel = Channel.fromWire(text(node, AgentEvent.FIELD_CHANNEL));
        String runId = text(node, AgentEvent.FIELD_RUN_ID);
        String agentId = text(node, AgentEvent.FIELD_AGENT_ID);
        String traceId = text(node, AgentEvent.FIELD_TRACE_ID);
        String spanId = text(node, AgentEvent.FIELD_SPAN_ID);
        long seq = node.path(AgentEvent.FIELD_SEQ).asLong(0L);
        long timestampMs = node.path(AgentEvent.FIELD_TS).asLong(0L);
        boolean terminal = node.path(AgentEvent.FIELD_TERMINAL).asBoolean(false);

        EventPayload data = readPayload(mapper, node.get(AgentEvent.FIELD_DATA), type);

        return new AgentEvent(type, channel, runId, agentId, traceId, spanId,
                seq, timestampMs, terminal, data);
    }

    /**
     * 把 {@code data} 段反序列化为 {@code type} 对应的负载类;{@code data} 缺失返回 null,
     * 未知 {@code type} 用 {@link EventPayload.Unknown} 兜底以保留原始字段。
     */
    private EventPayload readPayload(ObjectMapper mapper, JsonNode dataNode, EventType type)
            throws IOException {
        if (dataNode == null || dataNode.isNull()) {
            return null;
        }
        Class<? extends EventPayload> payloadType =
                type != null ? type.payloadType() : EventPayload.Unknown.class;
        return mapper.treeToValue(dataNode, payloadType);
    }

    /**
     * 读取文本字段;缺失 / null 节点返回 {@code null}。
     */
    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
