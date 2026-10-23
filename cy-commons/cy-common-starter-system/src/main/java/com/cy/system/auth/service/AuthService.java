package com.cy.system.auth.service;

import com.cy.system.auth.vo.LoginRequest;
import com.cy.system.auth.vo.LoginResponse;
import com.cy.system.auth.vo.RegisterRequest;
import com.cy.system.auth.vo.UserInfoVO;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 认证服务接口
 */
public interface AuthService {

    /**
     * 用户登录
     *
     * @param request 登录请求参数
     * @return 登录响应
     */
    LoginResponse login(LoginRequest request);

    /**
     * 用户登出
     *
     * @param accessToken token
     * @return 是否登出成功
     */
    Boolean logout(String accessToken);

    /**
     * 用户注册
     *
     * @param request 注册请求参数
     * @return 是否注册成功
     */
    Boolean register(RegisterRequest request);

    /**
     * 获取当前用户信息
     *
     * @param userId 用户ID
     * @return 用户信息
     */
    UserInfoVO getUserInfo(Long userId);
}
