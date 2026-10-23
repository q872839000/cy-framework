package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式响应选项
 * <p>
 * 控制流式（SSE）响应的附加行为。当请求的 {@code stream} 为 true 时，
 * 可通过此对象配置流式输出的额外特性。
 * </p>
 * <p>
 * 典型用法：设置 {@code includeUsage = true}，使流式响应的最后一个 chunk
 * 包含完整的 token 用量统计信息。
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
public class StreamOptions {

    /**
     * 是否在流式响应末尾包含 token 用量统计
     * <p>
     * 设置为 true 时，流结束前的最后一个 chunk 会携带
     * {@code usage} 字段，包含 prompt_tokens、completion_tokens、total_tokens。
     * </p>
     */
    private Boolean includeUsage;
}
