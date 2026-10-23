package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话消息模型
 * <p>
 * 对应 OpenAI Chat Completions API 中的 message 对象。
 * 每条消息由角色（role）和内容（content）组成，支持系统指令、用户输入、
 * 助手回复和工具调用结果四种角色类型。
 * </p>
 * <p>
 * 扩展字段说明：
 * - {@code reasoningContent}：深度思考内容，部分模型（如 DeepSeek-R1、Qwen3）
 *   在推理时会产生的中间推理过程，与最终回复内容（content）分离返回。
 *   厂商扩展字段（非 OpenAI 标准），仅响应方向有效，见字段级注释。
 * - {@code extra}：用于透传各厂商特有的非标准字段，序列化时平铺到 JSON 根级别。
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
public class ChatMessage {

    // ========================= 角色常量 =========================

    /** 角色：系统指令 */
    public static final String ROLE_SYSTEM = "system";

    /** 角色：用户输入 */
    public static final String ROLE_USER = "user";

    /** 角色：助手回复 */
    public static final String ROLE_ASSISTANT = "assistant";

    /** 角色：工具调用结果 */
    public static final String ROLE_TOOL = "tool";

    // ========================= OpenAI 标准字段 =========================

    /**
     * 消息角色
     * <p>
     * 取值范围：system / user / assistant / tool
     * </p>
     */
    private String role;

    /**
     * 消息文本内容
     * <p>
     * assistant 角色时可为 null（当存在 tool_calls 时）
     * </p>
     * <p>
     * 多模态说明：携带 {@link #contentParts} 时本字段仍持有其中的文本部分
     * （Java 侧读取语义不变），JSON 序列化形态由 {@code contentParts} 决定
     * （见 {@code contentForJson()}）。
     * </p>
     */
    private String content;

    /**
     * 多模态内容块列表（OpenAI content parts 规范，可选）
     * <p>
     * 非空时 {@code content} 在 JSON 线上序列化为数组形态（{@code [{type,text},
     * {type,image_url}, ...]}），为空时保持纯字符串形态——两种形态均为 OpenAI
     * 规范合法取值，vLLM / OpenAI / DashScope 兼容端点均接受。
     * </p>
     * <p>
     * 不参与独立序列化（对线缆不可见），仅经 {@code content} 属性出口。
     * </p>
     */
    @JsonIgnore
    private List<ContentPart> contentParts;

    /**
     * 消息来源名称（可选）
     * <p>
     * 用于区分同一角色下的不同参与者，便于模型区分多个系统指令或用户身份
     * </p>
     */
    private String name;

    /**
     * 助手发起的工具调用列表
     * <p>
     * 仅 assistant 角色消息包含此字段。模型决定调用工具时，
     * 会在此列表中返回一个或多个工具调用指令。
     * </p>
     */
    private List<ToolCall> toolCalls;

    /**
     * 关联的工具调用 ID
     * <p>
     * 仅 tool 角色消息需要此字段，用于将工具执行结果
     * 关联回 assistant 消息中对应的 tool_call。
     * </p>
     */
    private String toolCallId;

    // ========================= 扩展字段 =========================

    /**
     * 深度思考内容（厂商扩展字段，非 OpenAI 标准）
     * <p>
     * OpenAI Chat Completions 规范的 message 对象中没有此字段；它是 DeepSeek
     * （{@code reasoning_content}）、vLLM reasoning parser、Qwen3 等厂商/部署的扩展，
     * 仅出现在<b>响应</b>中。
     * </p>
     * <p>
     * 规范约束：请求方向的 messages 中不得携带此字段——DeepSeek 官方文档明确规定
     * 在输入 messages 里回传 reasoning_content 会返回 400 错误。故本字段声明为
     * {@code WRITE_ONLY}：只从响应反序列化进来，序列化（请求方向）时自动剔除，
     * 调用方拼装多轮历史时无需手工剥离。
     * </p>
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String reasoningContent;

    /**
     * 厂商特有的非标准扩展字段
     * <p>
     * 序列化时平铺到 JSON 根级别（通过 {@code @JsonAnyGetter}），
     * 反序列化时自动捕获未知字段（通过 {@code @JsonAnySetter}）。
     * 用于透传各厂商 API 中的自定义参数，无需修改模型代码。
     * </p>
     */
    @Builder.Default
    private Map<String, Object> extra = new LinkedHashMap<>();

    // ========================= 手动添加的 Getter 方法 =========================

    /**
     * 获取工具调用列表
     *
     * @return 工具调用列表
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * {@code content} 属性的序列化出口（显式声明，优先于 Lombok 生成的隐式 getter）
     * <p>
     * 携带内容块时输出数组形态（多模态规范），否则输出纯字符串形态。
     * 两者皆为 OpenAI 规范的合法 content 取值。
     * </p>
     *
     * @return 内容块列表或纯文本
     */
    @JsonGetter("content")
    Object contentForJson() {
        return contentParts != null && !contentParts.isEmpty() ? contentParts : content;
    }

    /**
     * {@code content} 属性的反序列化入口（显式声明，优先于 Lombok 生成的隐式 setter）
     * <p>
     * 响应方向按 OpenAI 规范兼容两种形态：纯文本直接入 {@link #content}；
     * 数组形态解析为 {@link #contentParts}，并把文本块拼接回填 {@link #content}
     * （下游日志 / 记忆等文本消费方无需感知多模态结构）。
     * </p>
     *
     * @param node content 原始 JSON 节点
     */
    @JsonSetter("content")
    void contentFromJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            this.content = node.asText();
            return;
        }
        if (!node.isArray()) {
            return;
        }
        List<ContentPart> parts = new ArrayList<>(node.size());
        StringBuilder text = new StringBuilder();
        for (JsonNode part : node) {
            String type = part.path("type").asText(null);
            if (ContentPart.TYPE_TEXT.equals(type)) {
                String textValue = part.path("text").asText(null);
                parts.add(ContentPart.ofText(textValue));
                if (textValue != null) {
                    text.append(textValue);
                }
            } else if (ContentPart.TYPE_IMAGE_URL.equals(type)) {
                JsonNode imageNode = part.path("image_url");
                parts.add(ContentPart.builder()
                        .type(ContentPart.TYPE_IMAGE_URL)
                        .imageUrl(ContentPart.ImageUrl.builder()
                                .url(imageNode.path("url").asText(null))
                                .detail(imageNode.path("detail").asText(null))
                                .build())
                        .build());
            }
            // 未识别的块类型（如 input_audio 等后续扩展）静默跳过，不阻断解析
        }
        this.contentParts = parts;
        this.content = text.length() > 0 ? text.toString() : null;
    }

    /**
     * 获取扩展字段（JSON 序列化时平铺到根级别）
     *
     * @return 扩展字段 Map
     */
    @JsonAnyGetter
    public Map<String, Object> getExtra() {
        return extra;
    }

    /**
     * 设置扩展字段（JSON 反序列化时捕获未知字段）
     *
     * @param key   字段名
     * @param value 字段值
     */
    @JsonAnySetter
    public void setExtra(String key, Object value) {
        if (extra == null) {
            extra = new LinkedHashMap<>();
        }
        extra.put(key, value);
    }

    // ========================= 静态工厂方法 =========================

    /**
     * 创建系统指令消息
     *
     * @param content 系统指令内容
     * @return 系统角色消息
     */
    public static ChatMessage system(String content) {
        return ChatMessage.builder()
                .role(ROLE_SYSTEM)
                .content(content)
                .build();
    }

    /**
     * 创建用户输入消息
     *
     * @param content 用户输入内容
     * @return 用户角色消息
     */
    public static ChatMessage user(String content) {
        return ChatMessage.builder()
                .role(ROLE_USER)
                .content(content)
                .build();
    }

    /**
     * 创建携带图片的用户输入消息（多模态）
     * <p>
     * 序列化时 content 输出为 OpenAI 规范的数组形态：文本块在前，图片块按序在后。
     * {@code content} 字段同步持有文本部分，Java 侧文本消费方（日志 / 记忆 / 持久化）
     * 无需感知内容块结构。
     * </p>
     *
     * @param content   用户输入文本（可为 null / 空白，此时仅图片块）
     * @param imageUrls 图片地址列表（data URI 或 http(s) URL，不能为空）
     * @return 用户角色消息
     */
    public static ChatMessage userWithImages(String content, List<String> imageUrls) {
        List<ContentPart> parts = new ArrayList<>(imageUrls.size() + 1);
        if (content != null && !content.isBlank()) {
            parts.add(ContentPart.ofText(content));
        }
        for (String url : imageUrls) {
            if (url != null && !url.isBlank()) {
                parts.add(ContentPart.ofImageUrl(url));
            }
        }
        return ChatMessage.builder()
                .role(ROLE_USER)
                .content(content)
                .contentParts(parts)
                .build();
    }

    /**
     * 创建助手回复消息
     *
     * @param content 助手回复内容
     * @return 助手角色消息
     */
    public static ChatMessage assistant(String content) {
        return ChatMessage.builder()
                .role(ROLE_ASSISTANT)
                .content(content)
                .build();
    }

    /**
     * 创建带有思考内容的助手回复消息
     * <p>
     * 注意：reasoningContent 是响应侧厂商扩展字段（{@code WRITE_ONLY}），
     * 序列化为请求时会被自动剔除，本工厂方法仅用于内存态表示与测试构造。
     * </p>
     *
     * @param content          助手回复内容
     * @param reasoningContent 深度思考过程内容
     * @return 助手角色消息
     */
    public static ChatMessage assistant(String content, String reasoningContent) {
        return ChatMessage.builder()
                .role(ROLE_ASSISTANT)
                .content(content)
                .reasoningContent(reasoningContent)
                .build();
    }

    /**
     * 创建工具调用结果消息
     *
     * @param toolCallId 关联的工具调用 ID
     * @param content    工具执行结果内容
     * @return 工具角色消息
     */
    public static ChatMessage tool(String toolCallId, String content) {
        return ChatMessage.builder()
                .role(ROLE_TOOL)
                .toolCallId(toolCallId)
                .content(content)
                .build();
    }

    // ========================= 内部模型 =========================

    /**
     * 工具调用对象
     * <p>
     * 当模型决定调用外部工具时，assistant 消息的 {@code toolCalls} 列表
     * 中会包含此对象。每个 ToolCall 包含唯一标识、调用类型和具体的函数信息。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {

        /** 工具调用的唯一标识，tool 角色消息通过 toolCallId 引用此 ID */
        private String id;

        /** 工具类型，目前仅支持 "function" */
        @Builder.Default
        private String type = "function";

        /** 具体的函数调用信息 */
        private FunctionCall function;
    }

    /**
     * 函数调用信息
     * <p>
     * 描述模型要求调用的具体函数，包括函数名称和由模型生成的 JSON 格式参数字符串。
     * </p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {

        /** 被调用的函数名称 */
        private String name;

        /** 函数参数，JSON 格式字符串（由模型生成，调用方需自行解析） */
        private String arguments;
    }
}
