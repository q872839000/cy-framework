package com.cy.core.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * @Author: pcy
 * @Date: 2026/1/12
 * @Description: Token 加密解密工具类
 * <p>
 * Token格式: {加密用户ID(16位hex)}{时间戳(8位hex)}{随机数(8位hex)}{校验码(8位hex)} = 40位纯hex字符串
 * <p>
 * 设计说明:
 * 1. 用户ID使用XOR+密钥混淆加密为16位hex，可逆解密获取用户ID
 * 2. 时间戳使用8位hex表示（精确到秒）
 * 3. 随机数使用8位hex增加Token随机性
 * 4. 校验码使用SHA256哈希的前8位，用于验证Token完整性
 * 5. 整个Token为纯hex字符串，无特殊分隔符
 * <p>
 * 密钥配置：通过环境变量或配置文件外部化，避免硬编码。
 * - CY_TOKEN_XOR_KEY: XOR 加密密钥（8字节 ASCII）
 * - CY_TOKEN_SIGN_SECRET: Token 签名密钥
 * - CY_TOKEN_USER_KEY_SALT: 用户缓存键盐值
 */
public class TokenCrypto {

    /**
     * XOR加密密钥（8字节，用于加密Long类型的用户ID）
     * 优先从环境变量 CY_TOKEN_XOR_KEY 读取，未配置时使用默认值。
     */
    private static final byte[] XOR_KEY = resolveXorKey();

    /**
     * Token 签名密钥
     * 优先从环境变量 CY_TOKEN_SIGN_SECRET 读取，未配置时使用默认值。
     */
    private static final String TOKEN_SIGN_SECRET = resolveSecret(
            "CY_TOKEN_SIGN_SECRET", "cy-token-sign-secret-2026");

    /**
     * 用户缓存键加密盐值
     * 优先从环境变量 CY_TOKEN_USER_KEY_SALT 读取，未配置时使用默认值。
     */
    private static final String USER_KEY_SALT = resolveSecret(
            "CY_TOKEN_USER_KEY_SALT", "cy-user-key-salt-2026");

    /**
     * 加密用户ID长度（16位hex）
     */
    public static final int ENCRYPTED_USER_ID_LENGTH = 16;

    /**
     * 时间戳长度（8位hex）
     */
    public static final int TIMESTAMP_LENGTH = 8;

    /**
     * 随机数长度（8位hex）
     */
    public static final int RANDOM_LENGTH = 8;

    /**
     * 校验码长度（8位hex）
     */
    public static final int CHECKSUM_LENGTH = 8;

    /**
     * Token总长度（40位hex）
     */
    public static final int TOKEN_LENGTH = ENCRYPTED_USER_ID_LENGTH + TIMESTAMP_LENGTH + RANDOM_LENGTH + CHECKSUM_LENGTH;

    /** 预编译的hex字符校验正则 */
    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]+$");

    /**
     * 生成 Token
     *
     * @param userId 用户 ID
     * @return 40位hex格式的Token
     */
    public static String generateToken(Long userId) {
        // 1. 加密用户ID为16位hex
        String encryptedUserId = encryptUserId(userId);

        // 2. 生成8位hex时间戳
        String timestamp = generateTimestamp();

        // 3. 生成8位hex随机数
        String random = generateRandom();

        // 4. 生成8位hex校验码
        String checksum = generateChecksum(encryptedUserId, timestamp, random);

        // 5. 组装Token
        return encryptedUserId + timestamp + random + checksum;
    }

    /**
     * 从 Token 中解析用户ID
     *
     * @param token Token 字符串
     * @return 用户ID，解析失败返回null
     */
    public static Long parseUserIdFromToken(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            return null;
        }

        try {
            // 提取加密的用户ID部分（前16位）
            String encryptedUserId = token.substring(0, ENCRYPTED_USER_ID_LENGTH);

            // 解密获取用户 ID
            return decryptUserId(encryptedUserId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 验证 Token 格式和校验码
     *
     * @param token Token 字符串
     * @return 是否有效
     */
    public static boolean validateTokenFormat(String token) {
        if (token == null || token.length() != TOKEN_LENGTH) {
            return false;
        }

        try {
            // 验证是否全为 hex字符
            if (!HEX_PATTERN.matcher(token).matches()) {
                return false;
            }

            // 提取各部分
            String encryptedUserId = token.substring(0, ENCRYPTED_USER_ID_LENGTH);
            String timestamp = token.substring(ENCRYPTED_USER_ID_LENGTH, ENCRYPTED_USER_ID_LENGTH + TIMESTAMP_LENGTH);
            String random = token.substring(ENCRYPTED_USER_ID_LENGTH + TIMESTAMP_LENGTH,
                    ENCRYPTED_USER_ID_LENGTH + TIMESTAMP_LENGTH + RANDOM_LENGTH);
            String checksum = token.substring(ENCRYPTED_USER_ID_LENGTH + TIMESTAMP_LENGTH + RANDOM_LENGTH);

            // 验证校验码
            String expectedChecksum = generateChecksum(encryptedUserId, timestamp, random);
            return checksum.equalsIgnoreCase(expectedChecksum);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成用户缓存键（基于用户ID加密）
     * 用于存储 用户ID -> Token 的映射，实现单点登录控制
     *
     * @param userId 用户 ID
     * @return 加密后的缓存键（16位hex）
     */
    public static String generateUserCacheKey(Long userId) {
        String data = userId + ":" + USER_KEY_SALT;
        String hash = sha256(data);
        // 返回16位hash作为缓存键
        return hash.substring(0, 16);
    }

    /**
     * 使用XOR+混淆加密用户ID为16位hex字符串
     * <p>
     * 加密过程:
     * 1. 将Long转为8字节数组
     * 2. 与密钥进行XOR运算
     * 3. 进行字节位置混淆
     * 4. 转为16位hex字符串
     *
     * @param userId 用户 ID
     * @return 16位hex字符串
     */
    public static String encryptUserId(Long userId) {
        // 1. 将Long转为8字节数组
        byte[] userIdBytes = longToBytes(userId);

        // 2. XOR加密
        byte[] encrypted = new byte[8];
        for (int i = 0; i < 8; i++) {
            encrypted[i] = (byte) (userIdBytes[i] ^ XOR_KEY[i]);
        }

        // 3. 字节位置混淆（简单的位置交换）
        byte[] shuffled = shuffleBytes(encrypted);

        // 4. 转为16位hex字符串
        return bytesToHex(shuffled);
    }

    /**
     * 解密16位hex字符串为用户ID
     *
     * @param encryptedHex 16位hex字符串
     * @return 用户 ID
     */
    public static Long decryptUserId(String encryptedHex) {
        try {
            // 1. hex转字节数组
            byte[] shuffled = hexToBytes(encryptedHex);

            // 2. 逆向字节位置混淆
            byte[] encrypted = unshuffleBytes(shuffled);

            // 3. XOR解密
            byte[] decrypted = new byte[8];
            for (int i = 0; i < 8; i++) {
                decrypted[i] = (byte) (encrypted[i] ^ XOR_KEY[i]);
            }

            // 4. 字节数组转Long
            return bytesToLong(decrypted);
        } catch (Exception e) {
            throw new RuntimeException("解密用户 ID失败", e);
        }
    }

    /**
     * 字节位置混淆
     * 混淆规则: [0,1,2,3,4,5,6,7] -> [1,3,5,7,0,2,4,6]
     */
    private static byte[] shuffleBytes(byte[] bytes) {
        byte[] result = new byte[8];
        result[0] = bytes[1];
        result[1] = bytes[3];
        result[2] = bytes[5];
        result[3] = bytes[7];
        result[4] = bytes[0];
        result[5] = bytes[2];
        result[6] = bytes[4];
        result[7] = bytes[6];
        return result;
    }

    /**
     * 逆向字节位置混淆
     * 逆向规则: [1,3,5,7,0,2,4,6] -> [0,1,2,3,4,5,6,7]
     */
    private static byte[] unshuffleBytes(byte[] bytes) {
        byte[] result = new byte[8];
        result[0] = bytes[4];
        result[1] = bytes[0];
        result[2] = bytes[5];
        result[3] = bytes[1];
        result[4] = bytes[6];
        result[5] = bytes[2];
        result[6] = bytes[7];
        result[7] = bytes[3];
        return result;
    }

    /**
     * 生成8位hex时间戳
     */
    private static String generateTimestamp() {
        long currentTime = System.currentTimeMillis() / 1000;
        return String.format("%08x", currentTime & 0xFFFFFFFFL);
    }

    /**
     * 生成8位hex随机数
     */
    private static String generateRandom() {
        int random = ThreadLocalRandom.current().nextInt();
        return String.format("%08x", random & 0xFFFFFFFFL);
    }

    /**
     * 生成8位hex校验码
     */
    private static String generateChecksum(String encryptedUserId, String timestamp, String random) {
        String data = encryptedUserId + timestamp + random + TOKEN_SIGN_SECRET;
        String hash = sha256(data);
        return hash.substring(0, CHECKSUM_LENGTH);
    }

    /**
     * SHA256哈希
     */
    public static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不支持", e);
        }
    }

    /**
     * 字节数组转 hex字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * hex 字符串转字节数组
     */
    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    /**
     * Long转8字节数组
     */
    private static byte[] longToBytes(long value) {
        byte[] bytes = new byte[8];
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    /**
     * 8字节数组转Long
     */
    private static long bytesToLong(byte[] bytes) {
        long value = 0;
        for (int i = 0; i < 8 && i < bytes.length; i++) {
            value = (value << 8) | (bytes[i] & 0xFF);
        }
        return value;
    }

    // ========================= 密钥外部化辅助方法 =========================

    /**
     * 从环境变量或系统属性解析 XOR 密钥。
     * 优先级：环境变量 CY_TOKEN_XOR_KEY → 系统属性 cy.token.xor-key → 默认值 "CyTk2026"
     */
    private static byte[] resolveXorKey() {
        String value = resolveSecret("CY_TOKEN_XOR_KEY", "cy.token.xor-key", null);
        if (value != null && !value.isEmpty()) {
            byte[] raw = value.getBytes(StandardCharsets.US_ASCII);
            if (raw.length >= 8) {
                byte[] key = new byte[8];
                System.arraycopy(raw, 0, key, 0, 8);
                return key;
            }
        }
        // 默认值（向后兼容）
        return new byte[]{0x43, 0x79, 0x54, 0x6B, 0x32, 0x30, 0x32, 0x36}; // "CyTk2026"
    }

    /**
     * 从环境变量或系统属性解析字符串密钥。
     *
     * @param envKey       环境变量名
     * @param defaultValue 默认值
     * @return 解析后的值
     */
    private static String resolveSecret(String envKey, String defaultValue) {
        String sysPropKey = envKey.toLowerCase().replace('_', '.');
        return resolveSecret(envKey, sysPropKey, defaultValue);
    }

    private static String resolveSecret(String envKey, String sysPropKey, String defaultValue) {
        // 1. 环境变量
        String value = System.getenv(envKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        // 2. 系统属性（支持 -D 参数和 Spring 外部化配置）
        value = System.getProperty(sysPropKey);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return defaultValue;
    }
}
