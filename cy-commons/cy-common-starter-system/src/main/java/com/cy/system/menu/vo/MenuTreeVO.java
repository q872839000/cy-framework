package com.cy.system.menu.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 菜单树形结构VO
 */
@Data
@Schema(description = "菜单树形结构")
public class MenuTreeVO {

    /**
     * 菜单ID
     */
    @Schema(description = "菜单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 父菜单ID
     */
    @Schema(description = "父菜单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /**
     * 菜单名称
     */
    @Schema(description = "菜单名称")
    private String menuName;

    /**
     * 菜单类型：1-目录，2-菜单，3-按钮
     */
    @Schema(description = "菜单类型：1-目录，2-菜单，3-按钮")
    private Integer menuType;

    /**
     * 路由地址
     */
    @Schema(description = "路由地址")
    private String path;

    /**
     * 组件路径
     */
    @Schema(description = "组件路径")
    private String component;

    /**
     * 权限标识
     */
    @Schema(description = "权限标识")
    private String permission;

    /**
     * 图标
     */
    @Schema(description = "图标")
    private String icon;

    /**
     * 排序
     */
    @Schema(description = "排序")
    private Integer sort;

    /**
     * 是否可见
     */
    @Schema(description = "是否可见")
    private Integer visible;

    /**
     * 状态
     */
    @Schema(description = "状态")
    private Integer status;

    /**
     * 子菜单列表
     */
    @Schema(description = "子菜单列表")
    private List<MenuTreeVO> children;
}
