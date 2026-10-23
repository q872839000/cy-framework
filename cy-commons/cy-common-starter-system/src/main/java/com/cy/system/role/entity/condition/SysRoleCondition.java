package com.cy.system.role.entity.condition;

import com.cy.core.base.Condition;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Author: pcy
 * @Date: 2026/1/14 10:31
 * @Description: 角色分页查询
 **/
@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "角色分页查询条件")
public class SysRoleCondition extends Condition {

    @Schema(description = "角色名（模糊查询）")
    private String name;
}
