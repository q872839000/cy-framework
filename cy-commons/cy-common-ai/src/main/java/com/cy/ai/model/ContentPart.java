package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多模态内容块（OpenAI Chat Completions content parts 规范）
 * <p>
 * 对应 OpenAI 消息 content 数组形态中的单个元素。当 {@link ChatMessage} 携带
 * 内容块列表时，其 {@code content} 字段序列化为数组形态（原生规范）：
 * <pre>
 * {"role":"user","content":[
 *   {"type":"text","text":"这张图里有什么"},
 *   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
 * ]}
 * </pre>
 * </p>
 * <p>
 * 图片地址支持两种形态（OpenAI / vLLM 均原生支持）：
 * <ul>
 *     <li>data URI：{@code data:image/jpeg;base64,...}（图片内联进请求体）</li>
 *     <li>http(s) URL：由模型服务端拉取（要求模型服务网络可达该地址）</li>
 * </ul>
 * </p>
 *
 * @author pcy
 * @since 2026/10/11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentPart {

    /** 内容块类型：文本 */
    public static final String TYPE_TEXT = "text";

    /** 内容块类型：图片地址 */
    public static final String TYPE_IMAGE_URL = "image_url";

    /**
     * 内容块类型（{@code text} / {@code image_url}）
     */
    private String type;

    /**
     * 文本内容（仅 {@code type=text} 时有效）
     */
    private String text;

    /**
     * 图片地址对象（仅 {@code type=image_url} 时有效）
     */
    private ImageUrl imageUrl;

    /**
     * 创建文本内容块
     *
     * @param text 文本内容
     * @return 文本内容块
     */
    public static ContentPart ofText(String text) {
        return ContentPart.builder().type(TYPE_TEXT).text(text).build();
    }

    /**
     * 创建图片内容块（detail 缺省，由模型服务端自行决定解析精度）
     *
     * @param url 图片地址（data URI 或 http(s) URL）
     * @return 图片内容块
     */
    public static ContentPart ofImageUrl(String url) {
        return ContentPart.builder().type(TYPE_IMAGE_URL)
                .imageUrl(ImageUrl.builder().url(url).build())
                .build();
    }

    /**
     * 图片地址对象（对应 OpenAI image_url 字段结构）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageUrl {

        /**
         * 图片地址：data URI（{@code data:image/...;base64,...}）或 http(s) URL
         */
        private String url;

        /**
         * 解析精度（OpenAI 规范：{@code low} / {@code high} / {@code auto}）
         * <p>
         * 为 null 时不下发，由模型服务端按默认处理；vLLM 等兼容端点
         * 不认识该字段时按惯例忽略。
         * </p>
         */
        private String detail;
    }
}
