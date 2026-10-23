package com.cy.conversation.session.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新会话请求VO
 * <p>
 * 仅支持更新会话标题
 * </p>
 *
 * @author pcy
 * @date 2026/1/26
 */
@Data
@Schema(description = "更新会话请求")
public class SessionUpdateRequest {

    /**
     * 会话ID
     */
    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "会话ID不能为空")
    private Long id;

    /**
     * 新的会话标题
     */
    @Schema(description = "新的会话标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "会话标题不能为空")
    @Size(min = 2, max = 50, message = "会话标题长度需在2-50个字符之间")
    private String title;
}
