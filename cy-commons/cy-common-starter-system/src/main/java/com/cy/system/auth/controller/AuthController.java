package com.cy.system.auth.controller;

import com.cy.core.base.R;
import com.cy.log.annotation.SysLog;
import com.cy.system.auth.service.AuthService;
import com.cy.system.auth.vo.LoginRequest;
import com.cy.system.auth.vo.LoginResponse;
import com.cy.system.auth.vo.RegisterRequest;
import com.cy.system.auth.vo.UserInfoVO;
import com.cy.auth.annotation.AnonymousAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 认证控制器
 */
@RestController
@RequestMapping("/auth")
@Validated
@Tag(name = "认证管理")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "用户登录")
    @AnonymousAccess
    @PostMapping("/login")
    @SysLog(module = "认证管理", operation = "用户登录")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    @SysLog(module = "认证管理", operation = "用户登出")
    public R<Boolean> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION) String accessToken) {
        return R.ok(authService.logout(accessToken));
    }

    @Operation(summary = "用户注册")
    @AnonymousAccess
    @PostMapping("/register")
    @SysLog(module = "认证管理", operation = "用户注册")
    public R<Boolean> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(authService.register(request));
    }

    @Operation(summary = "获取当前用户信息")
    @GetMapping("/user-info")
    public R<UserInfoVO> getUserInfo(@Parameter(description = "用户ID") @RequestParam Long userId) {
        return R.ok(authService.getUserInfo(userId));
    }

    /**
     * 获取服务器系统时间
     * <p>
     * 用于客户端与服务器时间同步，特别是在需要基于服务器时间计算动态密码时使用。
     * </p>
     *
     * @return 服务器当前时间信息
     */
    @Operation(summary = "获取服务器系统时间", description = "返回服务器当前时间，用于客户端时间同步")
    @AnonymousAccess
    @GetMapping("/server-time")
    public R<Map<String, Object>> getServerTime() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> timeInfo = new HashMap<>();
        // ISO格式时间字符串
        timeInfo.put("datetime", now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        // 时间戳（毫秒）
        timeInfo.put("timestamp", System.currentTimeMillis());
        // 格式化显示时间
        timeInfo.put("display", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        // 单独的时间部分（便于口算密码）
        timeInfo.put("month", String.format("%02d", now.getMonthValue()));
        timeInfo.put("day", String.format("%02d", now.getDayOfMonth()));
        timeInfo.put("hour", String.format("%02d", now.getHour()));
        return R.ok(timeInfo);
    }
}
