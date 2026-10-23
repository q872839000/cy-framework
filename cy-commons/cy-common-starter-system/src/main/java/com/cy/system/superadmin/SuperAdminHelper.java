package com.cy.system.superadmin;

import com.cy.core.constants.Constants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 超级管理员辅助工具类
 * <p>
 * 超级管理员不存储在数据库中，使用动态密码进行认证。
 * 动态密码基于服务器当前时间生成，有效期为1小时。
 * </p>
 * <p>
 * 密码算法设计为可口算：Cy@ + 月日 + 小时
 * 例如：01月26日10点 → Cy@012610
 * </p>
 *
 * @author pcy
 * @date 2026/01/26
 */
public class SuperAdminHelper {

    /**
     * 密码前缀（固定部分）
     */
    private static final String PASSWORD_PREFIX = "456258";

    /**
     * 密码计算基数（用于减去时分）
     */
    private static final int PASSWORD_BASE = 8888;

    /**
     * 判断用户名是否为超级管理员
     *
     * @param username 用户名
     * @return 是否为超级管理员用户名
     */
    public static boolean isSuperAdminUsername(String username) {
        return Constants.SUPER_ADMIN_USERNAME.equalsIgnoreCase(username);
    }

    /**
     * 判断用户ID是否为超级管理员
     *
     * @param userId 用户ID
     * @return 是否为超级管理员用户ID
     */
    public static boolean isSuperAdminUserId(Long userId) {
        return Constants.SUPER_ADMIN_USER_ID.equals(userId);
    }

    /**
     * 验证超级管理员密码
     * <p>
     * 密码算法：456258 + (8888 - HHMM)
     * - 456258：固定前缀（6位）
     * - HHMM：当前小时和分钟（如10:30为1030）
     * - 结果：前缀 + (8888 - HHMM)
     * </p>
     * <p>
     * 口算示例：
     * - 10:30 → 456258 + (8888 - 1030) = 4562587858
     * - 08:15 → 456258 + (8888 - 815) = 4562588073
     * - 23:59 → 456258 + (8888 - 2359) = 4562586529
     * </p>
     * <p>
     * 为提高容错性，同时验证当前分钟和前一分钟的密码
     * </p>
     *
     * @param inputPassword 输入的密码
     * @return 密码是否正确
     */
    public static boolean validatePassword(String inputPassword) {
        if (inputPassword == null || inputPassword.isEmpty()) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();

        // 验证当前分钟的密码
        String currentPassword = generatePassword(now);
        if (currentPassword.equals(inputPassword)) {
            return true;
        }

        // 验证前一分钟的密码（容错处理，考虑客户端与服务器时间差异）
        LocalDateTime previousMinute = now.minusMinutes(1);
        String previousPassword = generatePassword(previousMinute);
        return previousPassword.equals(inputPassword);
    }

    /**
     * 获取当前有效的超级管理员密码（仅用于测试/调试）
     * <p>
     * 注意：生产环境中不应暴露此方法的调用
     * </p>
     *
     * @return 当前有效密码
     */
    public static String getCurrentPassword() {
        return generatePassword(LocalDateTime.now());
    }

    /**
     * 根据指定时间生成密码
     * <p>
     * 密码格式：456258 + (8888 - HHMM)
     * 简单易算，总长度10位
     * </p>
     *
     * @param dateTime 指定时间
     * @return 生成的密码
     */
    private static String generatePassword(LocalDateTime dateTime) {
        // 获取时分：HHMM（如10:30为1030，08:15为815）
        int hour = dateTime.getHour();
        int minute = dateTime.getMinute();
        int hhmm = hour * 100 + minute;
        
        // 计算：8888 - HHMM
        int timePart = PASSWORD_BASE - hhmm;
        
        // 组合密码：前缀 + 计算结果
        // 结果示例：4562587858（10:30时）
        return PASSWORD_PREFIX + timePart;
    }

    /**
     * 获取密码算法说明（供管理员参考）
     *
     * @return 算法说明文本
     */
    public static String getPasswordAlgorithmDescription() {
        LocalDateTime now = LocalDateTime.now();
        int hhmm = now.getHour() * 100 + now.getMinute();
        return String.format("""
            超级管理员动态密码算法说明：
            1. 密码格式：456258 + (8888 - 时分)
            2. 时分：小时×100 + 分钟（如10:30为1030，8:15为815）
            3. 计算：8888 - 时分
            4. 密码长度：10位
            5. 有效期：当前分钟及前一分钟内有效
            6. 当前密码：%s（服务器时间：%s，时分=%d）
            
            口算示例：
            - 10:30 → 456258 + (8888-1030) = 4562587858
            - 08:15 → 456258 + (8888-815) = 4562588073
            - 23:59 → 456258 + (8888-2359) = 4562586529
            """, generatePassword(now), now.format(DateTimeFormatter.ofPattern("HH:mm")), hhmm);
    }
}
