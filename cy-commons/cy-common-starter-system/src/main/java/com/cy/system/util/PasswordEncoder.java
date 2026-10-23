package com.cy.system.util;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码加密工具类
 * <p>
 * 新密码统一使用 BCrypt（work factor 10）。
 * 验证时自动兼容旧版 MD5+Salt 格式（{base64_salt}${md5_hash}），
 * 调用方可通过 {@link #needsUpgrade(String)} 判断是否需要升级存储。
 * </p>
 *
 * @author pcy
 * @date 2026/1/6
 */
public class PasswordEncoder {

    private static final String MD5_SEPARATOR = "$";

    /**
     * BCrypt 前缀标识（所有 BCrypt 哈希以 $2a$、$2b$ 或 $2y$ 开头）
     */
    private static final String BCRYPT_PREFIX = "$2";

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(10);

    private PasswordEncoder() {
    }

    /**
     * 加密密码（使用 BCrypt）
     *
     * @param rawPassword 原始密码
     * @return BCrypt 哈希字符串
     */
    public static String encode(String rawPassword) {
        return BCRYPT.encode(rawPassword);
    }

    /**
     * 验证密码（自动兼容 BCrypt 和旧版 MD5+Salt）
     *
     * @param rawPassword     原始密码
     * @param encodedPassword 存储的密码哈希
     * @return 是否匹配
     */
    public static boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }
        if (isBcryptHash(encodedPassword)) {
            return BCRYPT.matches(rawPassword, encodedPassword);
        }
        // 旧版 MD5+Salt 兼容
        return matchesMd5(rawPassword, encodedPassword);
    }

    /**
     * 判断存储的密码是否需要升级到 BCrypt
     * <p>
     * 登录成功后调用此方法，如果返回 true，应使用 {@link #encode(String)}
     * 重新加密并更新数据库中的密码字段。
     * </p>
     *
     * @param encodedPassword 存储的密码哈希
     * @return true 表示仍为旧版 MD5 格式，需要升级
     */
    public static boolean needsUpgrade(String encodedPassword) {
        return encodedPassword != null && !isBcryptHash(encodedPassword);
    }

    /**
     * 判断是否为 BCrypt 哈希
     */
    private static boolean isBcryptHash(String encoded) {
        return encoded.startsWith(BCRYPT_PREFIX);
    }

    /**
     * 旧版 MD5+Salt 验证（向后兼容）
     */
    private static boolean matchesMd5(String rawPassword, String encodedPassword) {
        String[] parts = encodedPassword.split("\\" + MD5_SEPARATOR);
        if (parts.length != 2) {
            return false;
        }
        String salt = parts[0];
        String expectedHash = parts[1];
        String actualHash = md5Hash(rawPassword, salt);
        return expectedHash.equals(actualHash);
    }

    /**
     * MD5+Salt 哈希（仅用于旧密码验证，不再用于新密码生成）
     */
    private static String md5Hash(String password, String salt) {
        String saltedPassword = salt + password;
        return DigestUtils.md5DigestAsHex(saltedPassword.getBytes(StandardCharsets.UTF_8));
    }
}
