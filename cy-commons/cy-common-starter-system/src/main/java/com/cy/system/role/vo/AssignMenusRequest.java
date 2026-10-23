package com.cy.system.role.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 分配菜单请求参数
 */
@Data
@Schema(description = "分配菜单请求参数")
public class AssignMenusRequest {

    /**
     * 角色ID
     */
    @Schema(description = "角色ID")
    @NotNull(message = "角色ID不能为空")
    private Long roleId;

    /**
     * 菜单ID列表
     */
    @Schema(description = "菜单ID列表")
    private List<Long> menuIds;
}
