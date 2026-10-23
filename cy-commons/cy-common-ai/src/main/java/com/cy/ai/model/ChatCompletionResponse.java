package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话补全非流式响应模型
 * <p>
 * 对应 OpenAI Chat Completions API 的完整响应体。当请求的 {@code stream} 为 false
 * 或未设置时，服务端一次性返回此对象。
 * </p>
 * <p>
 * 响应中包含一个或多个候选回复（choices），每个 choice 包含完整的助手消息。
 * usage 字段提供本次请求的 token 消耗统计。
 * </p>
 *
 * @author pcy
 * @date 2026/03/09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {

    /**
     * 响应唯一标识
     * <p>
     * 由服务端生成，格式通常为 "chatcmpl-xxx"，可用于日志追踪和问题排查。
     * </p>
     */
    private String id;

    /**
     * 对象类型标识
     * <p>
     * 固定值 "chat.completion"，用于区分不同类型的 API 响应。
     * </p>
     */
    private String object;

    /**
     * 响应创建时间戳（Unix 秒）
     */
    private Long created;

    /**
     * 实际使用的模型标识
     * <p>
     * 服务端实际执行推理的模型名称，可能与请求中的 model 不完全一致
     * （例如请求 "gpt-4" 实际可能使用 "gpt-4-0613"）。
     * </p>
     */
    private String model;

    /**
     * 系统指纹
     * <p>
     * 标识服务端运行时环境的哈希值，用于确定性输出的复现验证。
     * 并非所有厂商都返回此字段。
     * </p>
     */
    private String systemFingerprint;

    /**
     * 候选回复列表
     * <p>
     * 包含一个或多个候选回复，数量由请求中的 {@code n} 参数决定。
     * 通常只有一个元素（index=0）。
     * </p>
     */
    private List<Choice> choices;

    /**
     * Token 用量统计
     */
    private Usage usage;

    // ========================= 便捷方法 =========================

    /**
     * 获取第一个候选回复的文本内容
     * <p>
     * 等价于 {@code choices[0].message.content}，
     * 适用于绝大多数只需要单个回复的场景。
     * </p>
     *
     * @return 第一个回复的文本内容，无法获取时返回 null
     */
    public String getFirstContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.getFirst();
        return first.getMessage() != null ? first.getMessage().getContent() : null;
    }

    /**
     * 获取第一个候选回复的深度思考内容
     * <p>
     * 等价于 {@code choices[0].message.reasoningContent}，
     * 仅在模型启用深度思考模式时有值。
     * </p>
     *
     * @return 第一个回复的思考内容，无法获取时返回 null
     */
    public String getFirstReasoningContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.getFirst();
        return first.getMessage() != null ? first.getMessage().getReasoningContent() : null;
    }

    /**
     * 获取本次请求消耗的总 token 数
     *
     * @return 总 token 数（prompt + completion），无统计信息时返回 0
     */
    public int getTotalTokens() {
        if (usage == null || usage.getTotalTokens() == null) {
            return 0;
        }
        return usage.getTotalTokens();
    }

    /**
     * 获取第一个候选回复的完整 ChatMessage
     * <p>
     * 用于 Function Calling 循环中获取 assistant 消息（含 tool_calls）。
     * </p>
     *
     * @return 第一个回复的 ChatMessage，无法获取时返回 null
     */
    public ChatMessage getFirstMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().getMessage();
    }

    /**
     * 判断第一个候选回复是否包含工具调用
     *
     * @return true 表示 LLM 要求调用工具
     */
    public boolean hasToolCalls() {
        ChatMessage msg = getFirstMessage();
        return msg != null && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty();
    }

    /**
     * 获取第一个候选回复的 finishReason
     *
     * @return finishReason 字符串，无法获取时返回 null
     */
    public String getFirstFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().getFinishReason();
    }

    // ========================= 内部模型 =========================

    /**
     * 候选回复
     * <p>
     * 表示模型生成的一个完整候选结果，包含回复消息和结束原因。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {

        /**
         * 候选序号（从 0 开始）
         */
        private Integer index;

        /**
         * 助手回复消息
         * <p>
         * 包含 role（固定为 assistant）、content、reasoning_content、tool_calls 等字段。
         * </p>
         */
        private ChatMessage message;

        /**
         * 生成结束原因
         * <p>
         * 取值说明：
         * - "stop"：模型自然结束或遇到停止序列
         * - "length"：达到 max_tokens 限制被截断
         * - "tool_calls"：模型决定调用工具
         * - "content_filter"：内容被安全过滤器拦截
         * </p>
         */
        private String finishReason;

        // 手动添加 getter 方法（Lombok 未生成）
        public Integer getIndex() {
            return index;
        }

        public ChatMessage getMessage() {
            return message;
        }

        public String getFinishReason() {
            return finishReason;
        }
    }

    /**
     * Token 用量统计
     * <p>
     * 记录本次请求中输入和输出的 token 消耗，用于计费和监控。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {

        /** 输入 token 数（系统指令 + 历史消息 + 用户输入） */
        private Integer promptTokens;

        /** 输出 token 数（模型生成的回复） */
        private Integer completionTokens;

        /** 总 token 数（prompt + completion） */
        private Integer totalTokens;

        // 手动添加 getter 方法（Lombok 未生成）
        public Integer getPromptTokens() {
            return promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public Integer getTotalTokens() {
            return totalTokens;
        }
    }
}
