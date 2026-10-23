package com.cy.core.constants;

/**
 * @Author: pcy
 * @Date: 2026/1/8 14:12
 * @Description: 常量接口
 **/
public interface Constants {

    /* ================ redis key ===============*/

    /**
     * 系统前缀
     */
    String SYSTEM_PRE = "system:";

    /**
     * 用户缓存键
     */
    String USER_CACHE_KEY = SYSTEM_PRE + "users";

    /**
     * Token缓存键前缀（token -> 用户信息）
     */
    String TOKEN_CACHE_KEY_PREFIX = SYSTEM_PRE + "auth:token:";

    /**
     * 用户Token映射缓存键前缀（用户ID加密键 -> token）
     * 用于单点登录控制：新登录时可通过此键找到旧token并清除
     */
    String USER_TOKEN_CACHE_KEY_PREFIX = SYSTEM_PRE + "auth:user:token:";


    /* ================ 超级管理员相关 ===============*/

    /**
     * 超级管理员用户名
     */
    String SUPER_ADMIN_USERNAME = "superadmin";

    /**
     * 超级管理员显示昵称
     */
    String SUPER_ADMIN_NICKNAME = "超级管理员";

    /**
     * 超级管理员虚拟用户ID（使用负数避免与数据库ID冲突）
     */
    Long SUPER_ADMIN_USER_ID = -1L;

    /**
     * 超级管理员角色编码
     */
    String SUPER_ADMIN_ROLE = "superadmin";

    /* ================ 符号 ===============*/
    /**
     * 空字符串
     */
    String STRING_EMPTY ="";
}
