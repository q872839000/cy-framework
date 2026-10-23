package com.cy.system.user.entity.condition;

import com.cy.core.base.Condition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户分页查询条件
 *
 * @author pcy
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "用户分页查询条件")
public class SysUserCondition extends Condition {

    @Schema(description = "用户名/昵称（模糊查询）")
    private String name;

    @Schema(description = "状态：0-禁用，1-正常")
    private Integer status;
}
