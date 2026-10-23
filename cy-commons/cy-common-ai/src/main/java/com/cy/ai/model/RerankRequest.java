package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Rerank 重排请求（OpenAI 兼容 /v1/rerank，对齐 Jina / vLLM / SiliconFlow 协议）。
 * <p>
 * 序列化依赖全局 SNAKE_CASE 命名策略（{@code topN} → {@code top_n}），
 * 与 {@link ChatCompletionRequest} 的 wire 约定一致。
 * </p>
 *
 * @author pcy
 * @since 2026/10/02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RerankRequest {

    /** 模型名（如 bge-reranker-v2-m3、rerank-multilingual-v3.0）。 */
    private String model;

    /** 查询文本。 */
    private String query;

    /** 待排序文档列表（按原始顺序，响应以 index 回指）。 */
    private List<String> documents;

    /** 只返回得分最高的前 N 条（可选，缺省由服务端决定）。 */
    private Integer topN;
}
