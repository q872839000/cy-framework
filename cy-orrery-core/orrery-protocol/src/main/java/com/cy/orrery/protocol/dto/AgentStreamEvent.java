// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Agent 流式事件
 * <p>
 * 定义 Agent 在流式模式下通过 SSE 推送给调用方的结构化事件。
 * Butler 接收后会映射为 {@link TaskEvent} 推送给最终客户端，
 * 以保留文本增量、状态变更、错误和完成等事件语义。
 * </p>
 *
 * @author pcy
 * @date 2026/04/15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Agent 流式事件")
public class AgentStreamEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 文本增量事件
     */
    public static final String TYPE_DELTA = "delta";

    /**
     * Agent 内部状态变更事件
     */
    public static final String TYPE_STATUS = "status";

    /**
     * 流式错误事件
     */
    public static final String TYPE_ERROR = "error";

    /**
     * 流式完成事件
     */
    public static final String TYPE_DONE = "done";

    /**
     * 事件类型
     */
    @Schema(description = "事件类型: delta / status / error / done")
    private String type;

    /**
     * 文本增量内容（type=delta）
     */
    @Schema(description = "文本增量内容")
    private String delta;

    /**
     * 状态描述（type=status）
     */
    @Schema(description = "状态描述")
    private String status;

    /**
     * 错误消息（type=error）
     */
    @Schema(description = "错误消息")
    private String error;

    /**
     * 扩展元数据
     */
    @Schema(description = "扩展元数据")
    private Map<String, Object> metadata;

    /**
     * 创建文本增量事件
     */
    public static AgentStreamEvent delta(String text) {
        return AgentStreamEvent.builder().type(TYPE_DELTA).delta(text).build();
    }

    /**
     * 创建状态变更事件
     */
    public static AgentStreamEvent status(String description) {
        return AgentStreamEvent.builder().type(TYPE_STATUS).status(description).build();
    }

    /**
     * 创建错误事件
     */
    public static AgentStreamEvent error(String message) {
        return AgentStreamEvent.builder().type(TYPE_ERROR).error(message).build();
    }

    /**
     * 创建流式完成事件
     */
    public static AgentStreamEvent done() {
        return AgentStreamEvent.builder().type(TYPE_DONE).build();
    }

    /**
     * 是否为文本增量事件
     */
    public boolean isDelta() {
        return TYPE_DELTA.equals(type);
    }

    /**
     * 是否为终止信号
     */
    public boolean isTerminal() {
        return TYPE_DONE.equals(type) || TYPE_ERROR.equals(type);
    }
}
