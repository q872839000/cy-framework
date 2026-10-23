package com.cy.core.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * MD5工具类
 *
 * @author pcy
 * @since 1.0.0
 */
public class Md5Utils {

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int BUFFER_SIZE = 8192;

    private Md5Utils() {
    }

    /**
     * 计算字节数组的MD5值
     *
     * @param data 字节数组
     * @return MD5哈希值（32位小写十六进制字符串）
     */
    public static String md5(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }

    /**
     * 计算输入流的MD5值
     *
     * @param inputStream 输入流
     * @return MD5哈希值（32位小写十六进制字符串）
     */
    public static String md5(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
            return bytesToHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        } catch (IOException e) {
            throw new RuntimeException("读取输入流失败", e);
        }
    }

    /**
     * 计算字符串的MD5值
     *
     * @param str 字符串
     * @return MD5哈希值（32位小写十六进制字符串）
     */
    public static String md5(String str) {
        if (str == null || str.isEmpty()) {
            return null;
        }
        return md5(str.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 字节数组转十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2] = HEX_DIGITS[v >>> 4];
            hexChars[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
        }
        return new String(hexChars);
    }
}
