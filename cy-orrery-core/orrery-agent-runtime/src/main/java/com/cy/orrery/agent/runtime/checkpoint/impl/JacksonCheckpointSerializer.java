// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.checkpoint.impl;


import com.cy.orrery.agent.runtime.checkpoint.Checkpoint;
import com.cy.orrery.agent.runtime.checkpoint.CheckpointSerializer;
import com.cy.orrery.agent.runtime.context.LoopMessage;
import com.cy.orrery.agent.runtime.harness.impl.DefaultContextGuard;
import com.cy.orrery.agent.runtime.hitl.HitlAttributes;
import com.cy.orrery.agent.runtime.hitl.SuspendedState;
import com.cy.orrery.agent.runtime.loop.ApprovalRequest;
import com.cy.orrery.agent.runtime.loop.DeferredToolCall;
import com.cy.orrery.agent.runtime.loop.LoopAttributes;
import com.cy.orrery.agent.runtime.loop.MicroStepDetector;
import com.cy.orrery.agent.runtime.pipeline.PipelineAttributes;
import com.cy.orrery.agent.runtime.plan.PlanLedger;
import com.cy.orrery.agent.runtime.plan.PlanLedgerAttributes;
import com.cy.orrery.agent.runtime.tool.ArgFingerprintFuse;
import com.cy.orrery.agent.runtime.tool.FailureStreakEscalator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jackson 检查点序列化实现（JSON 文本）。
 * <p>
 * 信封字段（runId / messages / stepCursor / compactionBoundary / revision /
 * updatedAtMillis）强类型往返；属性表按「键类型注册表」逐键还原：
 * 内核已知键（{@code loop.actionLog} / {@code loop.deferredCalls} /
 * {@code loop.pendingApprovals} / {@code hitl.suspendedState} /
 * {@code plan.ledger} / {@code pipeline.*}）已预注册强类型，
 * 宿主自定义属性键经 {@link #registerAttributeType} /
 * {@link #registerListAttributeType} 扩展；未注册键反序列化为通用
 * Map / List / 标量（宿主恢复后自行后处理）。
 * </p>
 *
 * @author pcy
 * @since 2026/08/27
 */
public class JacksonCheckpointSerializer implements CheckpointSerializer {

    private final ObjectMapper objectMapper;
    private final Map<String, JavaType> attributeTypes = new ConcurrentHashMap<>();

    /**
     * 构造序列化器（预注册内核已知属性键类型）。
     * <p>
     * 持有宿主 mapper 的防御性副本：宿主若启用 default typing（RPC 场景常见），
     * 序列化会把 {@code @class} 写入载荷、反序列化可实例化任意类（RCE 面）——
     * 副本显式停用多态默认类型，检查点载荷类型还原只走本类的键类型注册表。
     * </p>
     *
     * @param objectMapper JSON 编解码器
     */
    public JacksonCheckpointSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().deactivateDefaultTyping();
        // loop 包（键名一律引用各属性持有类的公开常量，单一事实源）
        registerListAttributeType(LoopAttributes.ACTION_LOG_KEY_NAME, String.class);
        registerListAttributeType(LoopAttributes.DEFERRED_CALLS_KEY_NAME, DeferredToolCall.class);
        registerListAttributeType(LoopAttributes.PENDING_APPROVALS_KEY_NAME, ApprovalRequest.class);
        registerAttributeType(LoopAttributes.TOTAL_ROUNDS_KEY_NAME, Integer.class);
        registerAttributeType(LoopAttributes.TOTAL_TOOL_CALLS_KEY_NAME, Integer.class);
        registerAttributeType(LoopAttributes.TOTAL_PROMPT_TOKENS_KEY_NAME, Integer.class);
        registerAttributeType(LoopAttributes.TOTAL_COMPLETION_TOKENS_KEY_NAME, Integer.class);
        // hitl 包
        registerAttributeType(HitlAttributes.SUSPENDED_STATE_KEY_NAME, SuspendedState.class);
        // plan 包
        registerAttributeType(PlanLedgerAttributes.LEDGER_KEY_NAME, PlanLedger.class);
        // pipeline 包
        registerAttributeType(PipelineAttributes.TRANSITIONS_KEY_NAME, Integer.class);
        registerAttributeType(PipelineAttributes.ROLLBACKS_KEY_NAME, Integer.class);
        // tool 包（harness 计数表：恢复后强类型往返，否则退化通用 Map 熔断计数失效）
        registerMapAttributeType(ArgFingerprintFuse.FUSE_COUNTS_KEY_NAME, Integer.class);
        registerMapAttributeType(FailureStreakEscalator.STREAK_COUNTS_KEY_NAME, Integer.class);
        registerMapAttributeType(FailureStreakEscalator.ESCALATED_KEY_NAME, Boolean.class);
        // loop 小步走计数 / harness 上下文守望高水位标记
        registerAttributeType(MicroStepDetector.COUNTER_KEY_NAME, Integer.class);
        registerAttributeType(DefaultContextGuard.HIGH_WATER_WARNED_KEY_NAME, Boolean.class);
    }

    /**
     * 注册属性键的强类型（宿主自定义属性恢复用）。
     *
     * @param keyName 属性键名（即 AttributeKey.name）
     * @param type    值类型
     */
    public void registerAttributeType(String keyName, Class<?> type) {
        attributeTypes.put(keyName, objectMapper.getTypeFactory().constructType(type));
    }

    /**
     * 注册属性键的 List 强类型。
     *
     * @param keyName     属性键名
     * @param elementType 元素类型
     */
    public void registerListAttributeType(String keyName, Class<?> elementType) {
        attributeTypes.put(keyName, objectMapper.getTypeFactory()
                .constructParametricType(List.class, elementType));
    }

    /**
     * 注册属性键的 Map&lt;String, V&gt; 强类型（计数表 / 标记表类属性）。
     *
     * @param keyName     属性键名
     * @param valueType   值类型
     */
    public void registerMapAttributeType(String keyName, Class<?> valueType) {
        attributeTypes.put(keyName, objectMapper.getTypeFactory()
                .constructParametricType(Map.class, String.class, valueType));
    }

    @Override
    public String serialize(Checkpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("检查点序列化失败 runId=" + checkpoint.runId(), e);
        }
    }

    @Override
    public Checkpoint deserialize(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            List<LoopMessage> messages = objectMapper.convertValue(
                    node.required("messages"),
                    objectMapper.getTypeFactory().constructParametricType(List.class, LoopMessage.class));
            return new Checkpoint(node.required("runId").asText(), messages,
                    readAttributes(node.get("attributes")),
                    node.path("stepCursor").asInt(),
                    node.path("compactionBoundary").asInt(),
                    node.path("revision").asLong(),
                    node.path("updatedAtMillis").asLong());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("检查点反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 读取属性表（逐键按键类型注册表还原）。
     *
     * @param attrsNode 属性表 JSON 对象（可空）
     * @return 属性名 → 还原值
     */
    private Map<String, Object> readAttributes(JsonNode attrsNode) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (attrsNode == null || !attrsNode.isObject()) {
            return attributes;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = attrsNode.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            attributes.put(entry.getKey(), convertAttribute(entry.getKey(), entry.getValue()));
        }
        return attributes;
    }

    /**
     * 按键类型注册表还原单个属性值。
     *
     * @param keyName 属性键名
     * @param value   JSON 值
     * @return 还原后的属性值
     */
    private Object convertAttribute(String keyName, JsonNode value) {
        JavaType type = attributeTypes.get(keyName);
        if (type == null) {
            return objectMapper.convertValue(value, Object.class);
        }
        return objectMapper.convertValue(value, type);
    }
}
