package com.cy.conversation.session.vo;

import com.cy.core.base.Condition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话查询条件VO
 *
 * @author pcy
 * @date 2026/1/26
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "会话查询条件")
public class SessionQueryCondition extends Condition {

    /**
     * 会话标题（模糊查询）
     */
    @Schema(description = "会话标题（模糊查询）")
    private String title;

    /**
     * 会话状态
     */
    @Schema(description = "会话状态：0-已归档，1-正常")
    private Integer status;
}
