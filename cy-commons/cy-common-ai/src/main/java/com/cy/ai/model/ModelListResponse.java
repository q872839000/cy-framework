package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 模型列表响应（OpenAI 兼容 GET /v1/models）。
 * <p>
 * 用于管理面"拉取模型列表"与能力探测；各厂商在 id/object 之外的扩展字段
 * （owned_by、created 等）一律忽略。
 * </p>
 *
 * @author pcy
 * @since 2026/10/12
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelListResponse {

    private String object;

    private List<ModelInfo> data;

    /**
     * 单个模型条目（仅取 id；其余字段厂商差异大，按需再扩）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelInfo {
        /** 模型标识（如 gpt-4o、bge-m3） */
        private String id;
        private String object;
    }

    /**
     * 便捷方法：提取全部模型 id（无数据返回空列表）。
     */
    public List<String> ids() {
        if (data == null) {
            return List.of();
        }
        return data.stream().map(ModelInfo::getId).filter(id -> id != null && !id.isBlank()).toList();
    }
}
