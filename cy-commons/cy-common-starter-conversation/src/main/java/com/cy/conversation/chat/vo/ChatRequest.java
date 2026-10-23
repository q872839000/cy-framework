package com.cy.conversation.chat.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求VO
 *
 * @author pcy
 * @date 2026/1/26
 */
@Data
@Schema(description = "聊天请求")
public class ChatRequest {

    /**
     * 会话ID
     * <p>
     * 如果为空，则自动创建新会话
     * </p>
     */
    @Schema(description = "会话ID（可选，不传则自动创建新会话）")
    private Long sessionId;

    /**
     * 用户消息内容
     */
    @Schema(description = "用户消息内容", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /**
     * 使用的模型
     * <p>
     * 可选，不传则使用默认模型
     * </p>
     */
    @Schema(description = "使用的模型（可选）")
    private String model;

    /**
     * 温度参数
     * <p>
     * 可选，控制输出的随机性（0.0-2.0）
     * </p>
     */
    @Schema(description = "温度参数（可选，0.0-2.0）")
    private Double temperature;

    /**
     * 最大Token数
     * <p>
     * 可选，限制输出的最大长度
     * </p>
     */
    @Schema(description = "最大Token数（可选）")
    private Integer maxTokens;

    /**
     * 系统提示
     * <p>
     * 可选，用于设置AI的行为和角色
     * </p>
     */
    @Schema(description = "系统提示（可选）")
    private String systemPrompt;

    /**
     * 历史消息数量限制
     * <p>
     * 可选，限制发送给AI的历史消息数量，默认10
     * </p>
     */
    @Schema(description = "历史消息数量限制（可选，默认10）")
    private Integer historyLimit;

    /**
     * 是否启用深度思考
     * <p>
     * 可选，部分模型支持，默认false
     * </p>
     */
    @Schema(description = "是否启用深度思考（可选，默认false）")
    private Boolean enableThinking;
}
