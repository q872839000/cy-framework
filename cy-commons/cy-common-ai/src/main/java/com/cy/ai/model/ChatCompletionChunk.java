package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话补全流式响应块模型
 * <p>
 * 对应 OpenAI Chat Completions API 在流式模式（stream=true）下返回的单个 SSE 数据块。
 * 每个 chunk 携带一个增量片段（delta），客户端需将多个 chunk 的 delta 按顺序拼接
 * 以还原完整的助手回复。
 * </p>
 * <p>
 * 流式响应的生命周期：
 * <ol>
 *     <li>首个 chunk：delta 中通常仅包含 role="assistant"</li>
 *     <li>中间 chunk：delta 中包含 content / reasoning_content / tool_calls 的增量片段</li>
 *     <li>末尾 chunk：finish_reason 不为 null，标志生成结束</li>
 *     <li>（可选）用量 chunk：当 stream_options.include_usage=true 时，最后额外返回一个携带 usage 的 chunk</li>
 * </ol>
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
public class ChatCompletionChunk {

    /**
     * 响应唯一标识
     * <p>
     * 同一次流式请求中所有 chunk 共享相同的 id。
     * </p>
     */
    private String id;

    /**
     * 对象类型标识
     * <p>
     * 固定值 "chat.completion.chunk"，区别于非流式的 "chat.completion"。
     * </p>
     */
    private String object;

    /**
     * 响应创建时间戳（Unix 秒）
     */
    private Long created;

    /**
     * 实际使用的模型标识
     */
    private String model;

    /**
     * 候选回复增量列表
     * <p>
     * 每个 chunk 通常只包含一个 choice（index=0）。
     * </p>
     */
    private List<Choice> choices;

    /**
     * Token 用量统计
     * <p>
     * 仅当请求中设置了 {@code stream_options.include_usage = true} 时，
     * 流的最后一个 chunk 才会包含此字段。
     * </p>
     */
    private ChatCompletionResponse.Usage usage;

    // ========================= 便捷方法 =========================

    /**
     * 获取当前 chunk 携带的文本内容增量
     *
     * @return 内容片段，无内容时返回 null
     */
    public String getContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Delta delta = choices.getFirst().getDelta();
        return delta != null ? delta.getContent() : null;
    }

    /**
     * 获取当前 chunk 携带的深度思考内容增量
     *
     * @return 思考内容片段，无内容时返回 null
     */
    public String getReasoningContent() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Delta delta = choices.getFirst().getDelta();
        return delta != null ? delta.getReasoningContent() : null;
    }

    /**
     * 判断当前流式响应是否已结束
     * <p>
     * 通过检查第一个 choice 的 finish_reason 是否非 null 来判断。
     * </p>
     *
     * @return true 表示生成已结束
     */
    public boolean isFinished() {
        if (choices == null || choices.isEmpty()) {
            return false;
        }
        return choices.getFirst().getFinishReason() != null;
    }

    /**
     * 获取生成结束原因
     *
     * @return 结束原因字符串，未结束时返回 null
     */
    public String getFinishReason() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        return choices.getFirst().getFinishReason();
    }

    // ========================= 内部模型 =========================

    /**
     * 流式候选回复
     * <p>
     * 与非流式 {@link ChatCompletionResponse.Choice} 的区别在于：
     * 使用 delta（增量）替代 message（完整消息）。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {

        /** 候选序号（从 0 开始） */
        private Integer index;

        /** 增量内容片段 */
        private Delta delta;

        /**
         * 生成结束原因
         * <p>
         * 中间 chunk 为 null，最后一个 chunk 取值与非流式相同：
         * stop / length / tool_calls / content_filter
         * </p>
         */
        private String finishReason;
    }

    /**
     * 增量内容片段
     * <p>
     * 每个 chunk 中的 delta 仅包含本次新增的内容片段，
     * 客户端需将所有 delta 按顺序拼接以还原完整回复。
     * </p>
     * <p>
     * 注意事项：
     * - 首个 chunk 的 delta 通常仅含 role，无 content
     * - content 和 reasoning_content 可能在同一个或不同的 chunk 中出现
     * - tool_calls 的 arguments 可能分多个 chunk 逐步传递
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Delta {

        /** 消息角色（仅首个 chunk 包含，固定为 "assistant"） */
        private String role;

        /** 文本内容增量片段 */
        private String content;

        /** 深度思考内容增量片段（厂商扩展字段，非 OpenAI 标准；DeepSeek / vLLM reasoning parser 等返回） */
        private String reasoningContent;

        /** 工具调用增量列表 */
        private List<ToolCallChunk> toolCalls;
    }

    /**
     * 工具调用增量片段
     * <p>
     * 流式模式下工具调用信息可能分多个 chunk 传递：
     * 第一个 chunk 包含 id、type、function.name，
     * 后续 chunk 逐步补充 function.arguments。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallChunk {

        /** 工具调用在列表中的序号 */
        private Integer index;

        /** 工具调用唯一标识（仅首个 chunk 包含） */
        private String id;

        /** 工具类型（仅首个 chunk 包含，固定为 "function"） */
        private String type;

        /** 函数调用信息增量 */
        private ChatMessage.FunctionCall function;
    }
}
