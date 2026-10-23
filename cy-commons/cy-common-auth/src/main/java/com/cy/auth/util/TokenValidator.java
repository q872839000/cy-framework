package com.cy.auth.util;

import com.cy.auth.constants.AuthConstants;
import com.cy.core.constants.Constants;
import com.cy.core.context.AuthUser;
import com.cy.core.utils.TokenCrypto;
import com.cy.redis.utils.RedisUtils;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * @Author: pcy
 * @Date: 2026/1/12
 * @Description: Token校验工具类
 */
public class TokenValidator {

    /**
     * 校验Token并返回用户信息
     *
     * @param token Token字符串
     * @return 用户信息，校验失败返回null
     */
    public static AuthUser validateToken(String token) {
        return validateToken(token,
                AuthConstants.DEFAULT_ACCESS_TOKEN_EXPIRES_IN,
                Math.max(1L, AuthConstants.DEFAULT_ACCESS_TOKEN_EXPIRES_IN / 2),
                true);
    }

    /**
     * 校验 Token 并在校验通过后按配置执行滑动续期。
     *
     * @param token Token字符串
     * @param accessTokenExpiresIn 续期后的目标有效期，单位：秒
     * @param accessTokenRenewThresholdIn 触发续期的剩余 TTL 阈值，单位：秒
     * @param renewTokenOnAccess 是否启用访问时续期
     * @return 用户信息，校验失败返回null
     */
    public static AuthUser validateToken(String token,
                                         long accessTokenExpiresIn,
                                         long accessTokenRenewThresholdIn,
                                         boolean renewTokenOnAccess) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        // 1. 格式校验
        if (!TokenCrypto.validateTokenFormat(token)) {
            return null;
        }

        // 2. 从Token中解析用户ID
        Long userId = TokenCrypto.parseUserIdFromToken(token);
        if (userId == null) {
            return null;
        }

        // 3. 从Redis获取缓存的用户信息
        String tokenCacheKey = Constants.TOKEN_CACHE_KEY_PREFIX + token;
        AuthUser user = RedisUtils.getCacheObject(tokenCacheKey);
        if (user == null) {
            return null;
        }

        // 4. 验证Token与缓存中的Token是否一致（防伪造）
        if (!token.equals(user.getAccessToken())) {
            return null;
        }

        // 5. 验证用户ID是否匹配
        if (!userId.equals(user.getUserId())) {
            return null;
        }

        // 6. 校验成功后按配置续期，确保只有真实有效 token 才能延长 Redis TTL
        renewTokenIfNecessary(token, tokenCacheKey, user, accessTokenExpiresIn, accessTokenRenewThresholdIn, renewTokenOnAccess);

        return user;
    }

    /**
     * 从Token中获取用户信息（仅解析，不校验Redis）
     * 用于网关已鉴权场景，从Redis获取用户信息
     *
     * @param token Token字符串
     * @return 用户信息，获取失败返回null
     */
    public static AuthUser getUserFromToken(String token) {
        if (StringUtils.isBlank(token)) {
            return null;
        }

        // 从Redis获取缓存的用户信息
        String tokenCacheKey = Constants.TOKEN_CACHE_KEY_PREFIX + token;
        return RedisUtils.getCacheObject(tokenCacheKey);
    }

    /**
     * 按需刷新 token 双 Redis key 的过期时间。
     * <p>
     * 只有 token 剩余 TTL 达到续期阈值，并且 user -> token 映射仍指向当前 token 时才续期。
     * 这样可以避免每次访问都写 Redis，也可以避免用户重新登录后旧 token 被续活。
     * </p>
     */
    private static void renewTokenIfNecessary(String token,
                                              String tokenCacheKey,
                                              AuthUser user,
                                              long accessTokenExpiresIn,
                                              long accessTokenRenewThresholdIn,
                                              boolean renewTokenOnAccess) {
        // 关闭续期、有效期非法或用户 ID 缺失时，不执行刷新
        if (!renewTokenOnAccess || accessTokenExpiresIn <= 0 || user.getUserId() == null) {
            return;
        }
        // 剩余 TTL 未进入续期窗口时不写 Redis，降低高频访问下的写入压力
        long tokenTtlMillis = RedisUtils.getTimeToLive(tokenCacheKey);
        if (tokenTtlMillis < 0 || tokenTtlMillis > Duration.ofSeconds(accessTokenRenewThresholdIn).toMillis()) {
            return;
        }
        // user -> token 映射不一致说明该 token 已被新登录替换，不能续活旧 token
        String userTokenKey = Constants.USER_TOKEN_CACHE_KEY_PREFIX + TokenCrypto.generateUserCacheKey(user.getUserId());
        String cachedToken = RedisUtils.getCacheObject(userTokenKey);
        if (!token.equals(cachedToken)) {
            return;
        }
        // token -> 用户信息 与 user -> token 两个 key 必须使用相同 TTL，保持单点登录控制一致
        Duration duration = Duration.ofSeconds(accessTokenExpiresIn);
        RedisUtils.expire(tokenCacheKey, duration);
        RedisUtils.expire(userTokenKey, duration);
    }
}
