package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Rerank 重排响应（OpenAI 兼容 /v1/rerank）。
 * <p>
 * 各厂商 results 元素结构一致（index + relevance_score），document 字段形态不一：
 * vLLM 返回纯文本，Jina 返回 {@code {"text": "..."}} 对象——故以 {@link JsonNode} 承接，
 * 由 {@link RerankResult#documentText()} 统一提取。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RerankResponse {

    private String model;

    private List<RerankResult> results;

    /**
     * 单条重排结果：以 index 回指请求 documents 中的原始位置。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RerankResult {

        /** 文档在请求 documents 中的下标。 */
        private int index;

        /** 相关性得分（relevance_score，越高越相关）。 */
        private Double relevanceScore;

        /** 服务端回传的文档内容（形态随厂商：纯文本或 {"text": ...} 对象，可为空）。 */
        private JsonNode document;

        /**
         * 统一提取文档文本：纯文本节点直接取值，对象节点取 text 字段，无内容返回 null。
         */
        public String documentText() {
            if (document == null || document.isNull()) {
                return null;
            }
            if (document.isTextual()) {
                return document.asText();
            }
            JsonNode text = document.get("text");
            return text != null && text.isTextual() ? text.asText() : null;
        }
    }
}
