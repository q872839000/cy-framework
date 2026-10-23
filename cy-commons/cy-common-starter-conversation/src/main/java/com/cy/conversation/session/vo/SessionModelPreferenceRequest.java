package com.cy.conversation.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 会话模型偏好更新请求
 * <p>
 * 全量提交语义：llmProvider / llmModel / thinkingPref 为 null = 清除会话级覆盖，跟随平台默认。
 * </p>
 *
 * @author pcy
 * @date 2026/10/02
 */
@Data
@Schema(description = "会话模型偏好更新请求")
public class SessionModelPreferenceRequest {

    /**
     * 会话ID
     */
    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话ID不能为空")
    private Long id;

    /**
     * LLM 提供商（cy.ai.providers 的 key；null = 清除覆盖）
     */
    @Schema(description = "LLM 提供商（null = 清除覆盖）")
    private String llmProvider;

    /**
     * LLM 模型覆盖（null = 提供商默认模型）
     */
    @Schema(description = "LLM 模型覆盖（null = 提供商默认模型）")
    private String llmModel;

    /**
     * 思考偏好（off / on / low / medium / high；null = 跟随 provider 全局配置）
     */
    @Schema(description = "思考偏好（null = 跟随 provider 全局配置）")
    private String thinkingPref;
}
