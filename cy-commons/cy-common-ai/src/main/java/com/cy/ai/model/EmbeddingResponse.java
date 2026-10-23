package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Embedding 响应（OpenAI 兼容 /v1/embeddings）。
 *
 * @author pcy
 * @since 2026/08/27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EmbeddingResponse {

    private String object;

    private List<EmbeddingData> data;

    private String model;

    private Usage usage;

    /**
     * 单条向量结果。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingData {
        private String object;
        private int index;
        private float[] embedding;
    }

    /**
     * token 用量。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {
        private int promptTokens;
        private int totalTokens;
    }

    /**
     * 便捷方法：取第 index 条文本的向量（按返回的 index 字段定位，而非列表位置）。
     */
    public float[] embeddingOf(int index) {
        if (data == null) {
            return null;
        }
        for (EmbeddingData d : data) {
            if (d.getIndex() == index) {
                return d.getEmbedding();
            }
        }
        return null;
    }

    /**
     * 向量维度（无数据返回 0）。
     */
    public int dimension() {
        float[] first = embeddingOf(0);
        return first == null ? 0 : first.length;
    }
}
