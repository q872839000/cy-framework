package com.cy.core.context;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * @Author: pcy
 * @Date: 2026/1/12
 * @Description: 认证用户信息
 */
@Data
public class AuthUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 昵称
     */
    private String nickname;

    /**
     * 头像
     */
    private String avatar;

    /**
     * 角色编码列表
     */
    private List<String> roles;

    /**
     * 权限编码列表
     */
    private List<String> permissions;

    /**
     * 访问令牌
     */
    private String accessToken;

    /**
     * 是否为超级管理员
     */
    private Boolean superAdmin = false;
}
