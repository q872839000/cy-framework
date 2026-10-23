package com.cy.system.menu.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.orm.core.domain.BaseEntity;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 系统菜单实体
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Table(value = "sys_menu")
@Schema(description = "系统菜单")
public class SysMenu extends BaseEntity {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "主键ID")
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
     * 是否可见：0-隐藏，1-显示
     */
    @Schema(description = "是否可见：0-隐藏，1-显示")
    private Integer visible;

    /**
     * 状态：0-禁用，1-正常
     */
    @Schema(description = "状态：0-禁用，1-正常")
    private Integer status;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;
}
