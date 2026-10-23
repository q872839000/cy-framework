// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工具轮编解码器：LLM arguments JSON 与参数 Map 互转、执行结果与 tool 消息 content 互转。
 * <p>
 * 从 {@link LoopToolRoundEngine} 抽出的纯编解码职责（主路径 / 重放 / 只读预执行共用）。
 * </p>
 *
 * @author pcy
 * @since 2026/09/02
 */
final class ToolRoundCodec {

    private static final Logger log = LoggerFactory.getLogger(ToolRoundCodec.class);

    private static final TypeReference<Map<String, Object>> ARGS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    ToolRoundCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 LLM arguments JSON 为参数 Map。
     * <p>
     * 解析失败返回 null（调用方生成失败回执、不执行工具）——绝不回退空 Map：
     * 半截 JSON 的"空参执行"会把解析错误伪装成合法无参调用，对写类工具是误执行。
     * </p>
     *
     * @param arguments 参数 JSON 字符串
     * @return 参数 Map；解析失败为 null
     */
    Map<String, Object> parseArgs(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(arguments, ARGS_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("工具参数 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 工具结果序列化为 tool 消息 content（回执 JSON 优先，失败兜底错误消息）。
     *
     * @param exec 工具执行结果
     * @return tool 消息 content
     */
    String serialize(ToolCallResult exec) {
        if (exec.resultJson() != null && !exec.resultJson().isBlank()) {
            return exec.resultJson();
        }
        return exec.errorMessage() == null ? "{}" : exec.errorMessage();
    }
}
