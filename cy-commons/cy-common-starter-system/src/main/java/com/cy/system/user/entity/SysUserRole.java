package com.cy.system.user.entity;

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
 * @Description: 用户角色关联实体
 */
@Data
@Table(value = "sys_user_role")
@Schema(description = "用户角色关联")
public class SysUserRole implements Serializable {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 角色ID
     */
    @Schema(description = "角色ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long roleId;

    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private Timestamp createTime;
}
