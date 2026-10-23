package com.cy.conversation.chat.vo;

import com.cy.core.base.R;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天流式响应VO
 * <p>
 * 用于SSE流式输出的响应数据结构
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "聊天流式响应")
public class ChatStreamResponse {

    /**
     * 响应类型
     * <p>
     * content - 内容片段
     * done - 完成标记
     * error - 错误信息
     * </p>
     */
    @Schema(description = "响应类型：content/done/error")
    private String type;

    /**
     * 内容片段
     */
    @Schema(description = "内容片段")
    private String content;

    /**
     * 思考内容片段
     */
    @Schema(description = "思考内容片段")
    private String reasoningContent;

    /**
     * 会话ID
     */
    @Schema(description = "会话ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /**
     * 消息ID（完成时返回）
     */
    @Schema(description = "消息ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;

    /**
     * Token消耗数（完成时返回）
     */
    @Schema(description = "Token消耗数")
    private Integer tokens;

    /**
     * 首帧响应耗时（毫秒，完成时返回）
     */
    @Schema(description = "首帧响应耗时（毫秒）")
    private Long firstTokenMs;

    /**
     * 总耗时（毫秒，完成时返回）
     */
    @Schema(description = "总耗时（毫秒）")
    private Long durationMs;

    /**
     * 错误信息（错误时返回）
     */
    @Schema(description = "错误信息")
    private String error;

    @Schema(description = "错误状态码")
    private Integer status;

    // ==================== 响应类型常量 ====================

    /**
     * 类型：内容片段
     */
    public static final String TYPE_CONTENT = "content";

    /**
     * 类型：完成
     */
    public static final String TYPE_DONE = "done";

    /**
     * 类型：错误
     */
    public static final String TYPE_ERROR = "error";

    // ==================== 静态工厂方法 ====================

    /**
     * 创建内容响应
     *
     * @param content 内容片段
     * @return 响应对象
     */
    public static ChatStreamResponse content(String content) {
        return ChatStreamResponse.builder()
                .type(TYPE_CONTENT)
                .content(content)
                .build();
    }

    /**
     * 创建思考内容响应
     *
     * @param reasoningContent 思考内容片段
     * @return 响应对象
     */
    public static ChatStreamResponse reasoning(String reasoningContent) {
        return ChatStreamResponse.builder()
                .type(TYPE_CONTENT)
                .reasoningContent(reasoningContent)
                .build();
    }

    /**
     * 创建完成响应
     *
     * @param sessionId    会话ID
     * @param messageId    消息ID
     * @param tokens       Token消耗数
     * @param firstTokenMs 首帧响应耗时
     * @param durationMs   总耗时
     * @return 响应对象
     */
    public static ChatStreamResponse done(Long sessionId, Long messageId, Integer tokens,
                                           Long firstTokenMs, Long durationMs) {
        return ChatStreamResponse.builder()
                .type(TYPE_DONE)
                .sessionId(sessionId)
                .messageId(messageId)
                .tokens(tokens)
                .firstTokenMs(firstTokenMs)
                .durationMs(durationMs)
                .build();
    }

    /**
     * 创建错误响应
     *
     * @param error 错误信息
     * @return 响应对象
     */
    public static ChatStreamResponse error(String error) {
        return error(null, error);
    }

    public static ChatStreamResponse error(Integer status, String error) {
        return ChatStreamResponse.builder()
                .type(TYPE_ERROR)
                .status(status)
                .error(error)
                .build();
    }

    public static ChatStreamResponse error(R<?> response) {
        if (response == null) {
            return error(null, null);
        }
        return error(response.getStatus(), response.getMessage());
    }
}
