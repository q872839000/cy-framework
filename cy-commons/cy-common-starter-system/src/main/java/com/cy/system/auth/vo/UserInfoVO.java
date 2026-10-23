package com.cy.system.auth.vo;

import com.cy.system.menu.vo.MenuTreeVO;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 用户信息VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户信息")
public class UserInfoVO {

    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;

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
     * 头像
     */
    @Schema(description = "头像")
    private String avatar;

    /**
     * 性别
     */
    @Schema(description = "性别")
    private Integer gender;

    /**
     * 是否为超级管理员
     */
    @Schema(description = "是否为超级管理员")
    private Boolean superAdmin;

    /**
     * 角色编码列表
     */
    @Schema(description = "角色编码列表")
    private List<String> roles;

    /**
     * 菜单列表
     */
    @Schema(description = "菜单列表")
    private List<MenuTreeVO> menus;
}
