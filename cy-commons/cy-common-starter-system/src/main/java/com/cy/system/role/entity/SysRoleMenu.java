package com.cy.system.role.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 角色菜单关联实体
 */
@Data
@Table(value = "sys_role_menu")
@Schema(description = "角色菜单关联")
public class SysRoleMenu implements Serializable {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 角色ID
     */
    @Schema(description = "角色ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long roleId;

    /**
     * 菜单ID
     */
    @Schema(description = "菜单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long menuId;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private Timestamp createTime;
}
