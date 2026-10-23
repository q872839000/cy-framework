package com.cy.system.user.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.orm.core.domain.BaseEntity;
import com.mybatisflex.annotation.Column;
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
 * @Description: 系统用户实体
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Table(value = "sys_user")
@Schema(description = "系统用户")
public class SysUser extends BaseEntity {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

    /**
     * 密码
     */
    @Schema(description = "密码")
    private String password;

    /**
     * 昵称
     */
    @Schema(description = "昵称")
    private String nickname;

    /**
     * 邮箱
     */
    @Schema(description = "邮箱")
    private String email;

    /**
     * 手机号
     */
    @Schema(description = "手机号")
    private String phone;

    /**
     * 头像（支持BASE64编码或图片URL，前端可直接渲染）
     */
    @Schema(description = "头像（支持BASE64编码或图片URL，前端可直接渲染）")
    private String avatar;

    /**
     * 性别：0-未知，1-男，2-女
     */
    @Schema(description = "性别：0-未知，1-男，2-女")
    private Integer gender;

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

    /**
     * 删除标志：0-未删除，1-已删除
     */
    @Schema(description = "删除标志：0-未删除，1-已删除")
    @Column(isLogicDelete = true)
    private Integer deleted;
}
