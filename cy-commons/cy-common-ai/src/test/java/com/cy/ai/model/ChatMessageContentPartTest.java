package com.cy.ai.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatMessage} 多模态 content 线缆契约测试
 * <p>
 * 对齐 OpenAI Chat Completions 规范：纯文本消息 content 序列化为字符串；
 * 携带内容块（contentParts）时序列化为数组形态。反序列化双向兼容两种形态。
 * 序列化配置与 {@code AiAutoConfiguration#aiObjectMapper()} 保持一致（SNAKE_CASE）。
 * </p>
 *
 * @author pcy
 * @since 2026/10/11
 */
class ChatMessageContentPartTest {

    /** 与生产一致的线缆序列化配置（SNAKE_CASE + NON_NULL） */
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void plainTextMessageSerializesContentAsString() throws Exception {
        String json = mapper.writeValueAsString(ChatMessage.user("你好"));

        JsonNode node = mapper.readTree(json);
        assertThat(node.get("content").isTextual()).isTrue();
        assertThat(node.get("content").asText()).isEqualTo("你好");
        // 多模态结构不外泄
        assertThat(node.has("content_parts")).isFalse();
    }

    @Test
    void imageMessageSerializesContentAsPartArray() throws Exception {
        ChatMessage message = ChatMessage.userWithImages("这张图里有什么",
                List.of("data:image/jpeg;base64,QUJD", "https://example.com/a.png"));

        JsonNode node = mapper.readTree(mapper.writeValueAsString(message));

        JsonNode content = node.get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(3);
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(0).get("text").asText()).isEqualTo("这张图里有什么");
        assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).get("image_url").get("url").asText())
                .isEqualTo("data:image/jpeg;base64,QUJD");
        assertThat(content.get(2).get("image_url").get("url").asText())
                .isEqualTo("https://example.com/a.png");
        // detail 未设置时不下发
        assertThat(content.get(1).get("image_url").has("detail")).isFalse();
        // Java 侧文本语义保持
        assertThat(message.getContent()).isEqualTo("这张图里有什么");
    }

    @Test
    void imageOnlyMessageSerializesWithoutTextPart() throws Exception {
        ChatMessage message = ChatMessage.userWithImages(null, List.of("data:image/png;base64,QUJD"));

        JsonNode content = mapper.readTree(mapper.writeValueAsString(message)).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("type").asText()).isEqualTo("image_url");
    }

    @Test
    void deserializesTextContent() {
        ChatMessage message = mapper.convertValue(
                mapper.createObjectNode().put("role", "assistant").put("content", "答复"),
                ChatMessage.class);

        assertThat(message.getContent()).isEqualTo("答复");
        assertThat(message.getContentParts()).isNull();
    }

    @Test
    void deserializesPartArrayContent() throws Exception {
        String json = """
                {"role":"user","content":[
                  {"type":"text","text":"前半"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,QUJD","detail":"low"}},
                  {"type":"text","text":"后半"}
                ]}
                """;

        ChatMessage message = mapper.readValue(json, ChatMessage.class);

        assertThat(message.getContentParts()).hasSize(3);
        assertThat(message.getContentParts().get(1).getImageUrl().getUrl())
                .isEqualTo("data:image/png;base64,QUJD");
        assertThat(message.getContentParts().get(1).getImageUrl().getDetail()).isEqualTo("low");
        // 文本块拼接回填 content，下游文本消费方无感知
        assertThat(message.getContent()).isEqualTo("前半后半");
    }

    @Test
    void reasoningContentStaysWriteOnly() throws Exception {
        // 请求方向不得回传 reasoning_content（DeepSeek 等厂商规范约束）
        ChatMessage message = ChatMessage.assistant("答复", "思考过程");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(message));
        assertThat(node.has("reasoning_content")).isFalse();
        assertThat(node.get("content").asText()).isEqualTo("答复");
    }
}
