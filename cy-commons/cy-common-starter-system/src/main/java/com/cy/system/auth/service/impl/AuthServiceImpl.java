package com.cy.system.auth.service.impl;

import com.cy.auth.config.AuthProperties;
import com.cy.core.constants.Constants;
import com.cy.core.context.AuthUser;
import com.cy.core.exception.CYException;
import com.cy.core.utils.TokenCrypto;
import com.cy.system.auth.service.AuthService;
import com.cy.system.auth.vo.LoginRequest;
import com.cy.system.auth.vo.LoginResponse;
import com.cy.system.auth.vo.RegisterRequest;
import com.cy.system.auth.vo.UserInfoVO;
import com.cy.system.menu.entity.SysMenu;
import com.cy.system.menu.service.SysMenuService;
import com.cy.system.menu.vo.MenuTreeVO;
import com.cy.system.role.entity.SysRole;
import com.cy.system.role.service.SysRoleService;
import com.cy.system.superadmin.SuperAdminHelper;
import com.cy.system.user.entity.SysUser;
import com.cy.system.user.service.SysUserService;
import com.cy.system.util.PasswordEncoder;
import com.cy.log.core.LogService;
import com.cy.log.core.LogType;
import com.cy.redis.utils.RedisUtils;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * @Auther: pcy
 * @Date: 2026/1/6
 * @Description: 认证服务实现类
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserService userService;
    private final SysRoleService roleService;
    private final SysMenuService menuService;
    private final AuthProperties authProperties;

    @Autowired(required = false)
    private LogService logService;

    /**
     * 超级管理员登录限流：5分钟内最多允许 5 次失败尝试
     */
    private static final String SUPER_ADMIN_RATE_KEY = "system:auth:superadmin:attempts";
    private static final int SUPER_ADMIN_MAX_ATTEMPTS = 5;
    private static final Duration SUPER_ADMIN_LOCKOUT_DURATION = Duration.ofMinutes(5);

    @Override
    public LoginResponse login(LoginRequest request) {
        // 检查是否为超级管理员登录
        if (SuperAdminHelper.isSuperAdminUsername(request.getUsername())) {
            return handleSuperAdminLogin(request);
        }

        // 根据用户名查询用户
        SysUser user = userService.getUserByUsername(request.getUsername());
        if (user == null) {
            throw new CYException(10000, request.getUsername());
        }

        // 验证密码
        if (!PasswordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CYException(10001);
        }

        // 透明升级：旧版 MD5 密码自动迁移到 BCrypt
        if (PasswordEncoder.needsUpgrade(user.getPassword())) {
            userService.resetPassword(user.getId(), request.getPassword());
        }

        // 检查用户状态
        if (user.getStatus() != 1) {
            throw new CYException(10002);
        }

        // 获取用户角色编码列表
        List<Long> roleIds = userService.getUserRoleIds(user.getId());
        List<String> roleCodes = getRoleCodesByRoleIds(roleIds);

        // 获取用户菜单权限编码列表
        List<String> permissions = getPermissionsByRoleIds(roleIds);

        // 生成访问令牌（使用TokenCrypto工具类）
        String accessToken = TokenCrypto.generateToken(user.getId());

        // 构建用户缓存对象（包含Token信息）
        AuthUser cacheUser = buildAuthUser(user, roleCodes, permissions, accessToken, false);

        // 清除旧Token（如果存在），实现单点登录
        clearOldToken(user.getId());

        // 存储双键：两个 key 使用同一份配置 TTL，保证登录有效期、续期目标有效期和响应 expiresIn 一致
        // 1. token -> 用户信息（用于Token验证后获取用户信息）
        long expiresIn = authProperties.getEffectiveAccessTokenExpiresIn();
        Duration tokenTtl = Duration.ofSeconds(expiresIn);
        String tokenCacheKey = Constants.TOKEN_CACHE_KEY_PREFIX + accessToken;
        RedisUtils.setCacheObject(tokenCacheKey, cacheUser, tokenTtl);

        // 2. 用户ID加密键 -> token（用于单点登录控制，新登录时可清除旧token）
        String userTokenKey = Constants.USER_TOKEN_CACHE_KEY_PREFIX + TokenCrypto.generateUserCacheKey(user.getId());
        RedisUtils.setCacheObject(userTokenKey, accessToken, tokenTtl);

        // 审计日志：普通用户登录成功
        audit("用户登录", "User", user.getId().toString(),
                "用户 " + user.getUsername() + " 登录成功", true, null);

        // 构建登录响应
        return LoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .accessToken(accessToken)
                .expiresIn(expiresIn)
                .roles(roleCodes)
                .permissions(permissions)
                .superAdmin(false)
                .build();
    }

    /**
     * 处理超级管理员登录
     * <p>
     * 超级管理员不存储在数据库中，使用动态密码验证。
     * 登录成功后具备所有系统权限。
     * </p>
     *
     * @param request 登录请求
     * @return 登录响应
     */
    private LoginResponse handleSuperAdminLogin(LoginRequest request) {
        // 限流检查：防止暴力破解超级管理员密码
        long attempts = RedisUtils.getAtomicValue(SUPER_ADMIN_RATE_KEY);
        if (attempts >= SUPER_ADMIN_MAX_ATTEMPTS) {
            throw new CYException(10008, "5分钟");
        }

        // 验证超级管理员动态密码
        if (!SuperAdminHelper.validatePassword(request.getPassword())) {
            // 记录失败次数
            long currentAttempts = RedisUtils.incrAtomicValue(SUPER_ADMIN_RATE_KEY, SUPER_ADMIN_LOCKOUT_DURATION);

            // 审计日志：超管登录失败
            audit("超管登录", "SuperAdmin", Constants.SUPER_ADMIN_USER_ID.toString(),
                    "超级管理员登录失败，第 " + currentAttempts + " 次尝试", false, "密码错误");

            throw new CYException(10001);
        }

        // 登录成功，清除失败计数
        RedisUtils.deleteObject(SUPER_ADMIN_RATE_KEY);

        // 获取所有菜单权限（超级管理员拥有所有权限）
        List<SysMenu> allMenus = menuService.listAll();
        List<String> allPermissions = allMenus.stream()
                .map(SysMenu::getPermission)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        // 超级管理员角色列表
        List<String> roleCodes = List.of(Constants.SUPER_ADMIN_ROLE);

        // 生成访问令牌（使用超级管理员虚拟用户ID）
        String accessToken = TokenCrypto.generateToken(Constants.SUPER_ADMIN_USER_ID);

        // 构建超级管理员用户缓存对象
        AuthUser cacheUser = buildSuperAdminAuthUser(roleCodes, allPermissions, accessToken);

        // 清除旧Token（如果存在）
        clearOldToken(Constants.SUPER_ADMIN_USER_ID);

        // 存储双键：超级管理员 token 与普通用户使用相同的有效期配置和续期策略
        // 1. token -> 用户信息（用于Token验证后获取用户信息）
        long expiresIn = authProperties.getEffectiveAccessTokenExpiresIn();
        Duration tokenTtl = Duration.ofSeconds(expiresIn);
        String tokenCacheKey = Constants.TOKEN_CACHE_KEY_PREFIX + accessToken;
        RedisUtils.setCacheObject(tokenCacheKey, cacheUser, tokenTtl);

        // 2. 用户ID加密键 -> token（用于单点登录控制，新登录时可清除旧token）
        String userTokenKey = Constants.USER_TOKEN_CACHE_KEY_PREFIX + TokenCrypto.generateUserCacheKey(Constants.SUPER_ADMIN_USER_ID);
        RedisUtils.setCacheObject(userTokenKey, accessToken, tokenTtl);

        // 审计日志：超管登录成功
        audit("超管登录", "SuperAdmin", Constants.SUPER_ADMIN_USER_ID.toString(),
                "超级管理员登录成功", true, null);

        // 构建登录响应
        return LoginResponse.builder()
                .userId(Constants.SUPER_ADMIN_USER_ID)
                .username(Constants.SUPER_ADMIN_USERNAME)
                .nickname(Constants.SUPER_ADMIN_NICKNAME)
                .avatar(null)
                .accessToken(accessToken)
                .expiresIn(expiresIn)
                .roles(roleCodes)
                .permissions(allPermissions)
                .superAdmin(true)
                .build();
    }

    /**
     * 构建超级管理员用户缓存对象
     *
     * @param roleCodes   角色编码列表
     * @param permissions 权限编码列表
     * @param token       访问令牌
     * @return 超级管理员用户缓存对象
     */
    private AuthUser buildSuperAdminAuthUser(List<String> roleCodes, List<String> permissions, String token) {
        AuthUser authUser = new AuthUser();
        authUser.setUserId(Constants.SUPER_ADMIN_USER_ID);
        authUser.setUsername(Constants.SUPER_ADMIN_USERNAME);
        authUser.setNickname(Constants.SUPER_ADMIN_NICKNAME);
        authUser.setAvatar(null);
        authUser.setRoles(roleCodes);
        authUser.setPermissions(permissions);
        authUser.setAccessToken(token);
        authUser.setSuperAdmin(true);
        return authUser;
    }

    @Override
    public Boolean logout(String accessToken) {
        try {
            Long userId = TokenCrypto.parseUserIdFromToken(accessToken);
            // 清除 Token 相关缓存
            clearOldToken(userId);

            // 审计日志：用户登出
            audit("用户登出", "User", userId.toString(),
                    "用户登出成功", true, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清除用户的旧Token
     * 通过用户ID加密键找到旧token，然后删除两个缓存键
     *
     * @param userId 用户 ID
     */
    private void clearOldToken(Long userId) {
        // 获取用户 Token 映射键
        String userTokenKey = Constants.USER_TOKEN_CACHE_KEY_PREFIX + TokenCrypto.generateUserCacheKey(userId);

        // 获取旧 Token
        String oldToken = RedisUtils.getCacheObject(userTokenKey);
        if (oldToken != null) {
            // 删除旧 Token 对应的用户信息缓存
            RedisUtils.deleteObject(Constants.TOKEN_CACHE_KEY_PREFIX + oldToken);
        }

        // 删除用户 Token 映射
        RedisUtils.deleteObject(userTokenKey);
    }

    @Override
    @Transactional
    public Boolean register(RegisterRequest request) {
        // 验证两次密码是否一致
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new CYException(10005);
        }

        // 检查用户名是否已存在
        SysUser existUser = userService.getUserByUsername(request.getUsername());
        if (existUser != null) {
            throw new CYException(10003, request.getUsername());
        }

        // 创建用户
        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setPassword(request.getPassword()); // SysUserServiceImpl.createUser中会加密
        user.setNickname(request.getNickname());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setStatus(1);

        Boolean result = userService.createUser(user);

        // 审计日志：用户注册
        if (Boolean.TRUE.equals(result)) {
            audit("用户注册", "User", user.getId() != null ? user.getId().toString() : request.getUsername(),
                    "新用户注册: " + request.getUsername(), true, null);
        }

        return result;
    }

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        // 检查是否为超级管理员
        if (SuperAdminHelper.isSuperAdminUserId(userId)) {
            return getSuperAdminInfo();
        }

        SysUser user = userService.getUserById(userId);
        if (user == null) {
            throw new CYException(10006);
        }

        // 获取用户角色编码列表
        List<Long> roleIds = userService.getUserRoleIds(userId);
        List<String> roleCodes = getRoleCodesByRoleIds(roleIds);

        // 获取用户菜单树
        List<MenuTreeVO> menus = menuService.getMenuTreeByRoleIds(roleIds);

        // 构建响应
        return UserInfoVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .email(user.getEmail())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .gender(user.getGender())
                .superAdmin(false)
                .roles(roleCodes)
                .menus(menus)
                .build();
    }

    /**
     * 获取超级管理员用户信息
     *
     * @return 超级管理员用户信息VO
     */
    private UserInfoVO getSuperAdminInfo() {
        // 获取所有菜单树（超级管理员拥有所有菜单）
        List<MenuTreeVO> allMenus = menuService.getMenuTree();

        return UserInfoVO.builder()
                .userId(Constants.SUPER_ADMIN_USER_ID)
                .username(Constants.SUPER_ADMIN_USERNAME)
                .nickname(Constants.SUPER_ADMIN_NICKNAME)
                .email(null)
                .phone(null)
                .avatar(null)
                .gender(0)
                .superAdmin(true)
                .roles(List.of(Constants.SUPER_ADMIN_ROLE))
                .menus(allMenus)
                .build();
    }

    /**
     * 获取用户角色编码列表（批量查询优化）
     *
     * @param roleIds 角色 ID 列表
     * @return 角色编码列表
     */
    private List<String> getRoleCodesByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        
        // 批量查询角色
        List<SysRole> roles = roleService.getRolesByIds(roleIds);
        return roles.stream()
                .map(SysRole::getRoleCode)
                .toList();
    }

    /**
     * 获取用户菜单权限编码列表（批量查询优化）
     *
     * @param roleIds 角色 ID 列表
     * @return 权限编码列表
     */
    private List<String> getPermissionsByRoleIds(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        
        // 批量查询菜单
        List<SysMenu> menus = menuService.getMenusByRoleIds(roleIds);
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }
        
        // 使用Stream API提取权限编码并去重
        return menus.stream()
                .map(SysMenu::getPermission)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
    }

    /**
     * 记录认证审计日志
     *
     * @param operation    操作类型
     * @param targetType   操作对象类型
     * @param targetId     操作对象ID
     * @param description  操作描述
     * @param success      是否成功
     * @param errorMessage 错误信息（成功时为null）
     */
    private void audit(String operation, String targetType, String targetId,
                       String description, boolean success, String errorMessage) {
        if (logService == null) {
            return;
        }
        LogService.LogBuilder builder = logService.builder()
                .logType(LogType.AUDIT)
                .module("认证管理")
                .operation(operation)
                .targetType(targetType)
                .targetId(targetId)
                .description(description)
                .success(success);
        if (errorMessage != null) {
            builder.errorMessage(errorMessage);
        }
        builder.record();
    }

    private AuthUser buildAuthUser(SysUser user, List<String> roleCodes, List<String> permissions, String token, boolean isSuperAdmin) {
        AuthUser authUser = new AuthUser();
        authUser.setUserId(user.getId());
        authUser.setUsername(user.getUsername());
        authUser.setNickname(user.getNickname());
        authUser.setAvatar(user.getAvatar());
        authUser.setRoles(roleCodes);
        authUser.setPermissions(permissions);
        authUser.setAccessToken(token);
        authUser.setSuperAdmin(isSuperAdmin);
        return authUser;
    }
}
