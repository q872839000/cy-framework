package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Embedding 请求（OpenAI 兼容 /v1/embeddings）。
 *
 * @author pcy
 * @since 2026/08/27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbeddingRequest {

    /** 模型名（如 bge-m3、text-embedding-3-small）。 */
    private String model;

    /** 待向量化文本（单条或多条批量）。 */
    private List<String> input;

    /** 向量维度（可选，部分模型支持降维）。 */
    private Integer dimensions;

    /** 编码格式（float / base64，默认 float）。 */
    private String encodingFormat;

    /** 终端用户标识（可选，审计用）。 */
    private String user;
}
