package com.cy.system.auth.vo;

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
 * @Description: 登录响应参数
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "登录响应参数")
public class LoginResponse {

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
     * 头像
     */
    @Schema(description = "头像")
    private String avatar;

    /**
     * 访问令牌
     */
    @Schema(description = "访问令牌")
    private String accessToken;

    /**
     * 令牌类型
     */
    @Schema(description = "令牌类型")
    @Builder.Default
    private String tokenType = "Bearer";

    /**
     * 过期时间（秒）
     */
    @Schema(description = "过期时间（秒）")
    private Long expiresIn;

    /**
     * 角色编码列表
     */
    @Schema(description = "角色编码列表")
    private List<String> roles;

    @Schema(description = "权限编码列表")
    private List<String> permissions;

    /**
     * 是否为超级管理员
     */
    @Schema(description = "是否为超级管理员")
    @Builder.Default
    private Boolean superAdmin = false;
}
