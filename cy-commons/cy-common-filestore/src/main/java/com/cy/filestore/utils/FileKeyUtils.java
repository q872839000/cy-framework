package com.cy.filestore.utils;

import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件键工具类
 * <p>
 * 统一处理文件键生成、路径前缀规范化与安全校验，确保本地存储与对象存储
 * 使用一致的 fileKey 规则。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public final class FileKeyUtils {

    private static final DateTimeFormatter DATE_PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private FileKeyUtils() {
    }

    /**
     * 生成最终文件键。
     * <p>
     * 当外部传入 {@code fileKey} 时，将其视为业务目录或对象键前缀；
     * 否则按 {@code yyyy/MM/dd} 自动生成日期目录。
     * 最终文件名统一使用 UUID 并保留原始扩展名。
     * </p>
     *
     * @param fileKey          业务目录或对象键前缀
     * @param originalFilename 原始文件名
     * @return 统一生成的文件键
     */
    public static String generateFileKey(String fileKey, String originalFilename) {
        String directory = normalizeRelativePath(fileKey);
        if (StringUtils.isBlank(directory)) {
            directory = LocalDate.now().format(DATE_PATH_FORMATTER);
        }
        return directory + "/" + buildGeneratedFilename(originalFilename);
    }

    /**
     * 规范化相对路径。
     * <p>
     * 处理规则：
     * </p>
     * <ol>
     *   <li>统一使用 {@code /} 作为分隔符</li>
     *   <li>去除首尾斜杠</li>
     *   <li>清理 {@code .} 当前目录段</li>
     *   <li>拒绝 {@code ..} 父级目录，避免越界访问</li>
     * </ol>
     *
     * @param path 待规范化路径
     * @return 规范化后的相对路径；若输入为空，则返回空字符串
     */
    public static String normalizeRelativePath(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }

        String normalized = StringUtils.trim(path).replace('\\', '/');
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        normalized = StringUtils.strip(normalized, "/");
        if (StringUtils.isBlank(normalized)) {
            return "";
        }

        String[] segments = normalized.split("/");
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("fileKey 包含非法父级目录: " + path);
            }
            if (StringUtils.isBlank(segment) || ".".equals(segment)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(segment);
        }
        return builder.toString();
    }

    /**
     * 生成随机文件名。
     *
     * @param originalFilename 原始文件名
     * @return 生成后的文件名
     */
    private static String buildGeneratedFilename(String originalFilename) {
        String extension = extractExtension(originalFilename);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return uuid + extension;
    }

    /**
     * 提取原始文件扩展名。
     *
     * @param originalFilename 原始文件名
     * @return 扩展名（包含点号）；若不存在扩展名则返回空字符串
     */
    private static String extractExtension(String originalFilename) {
        if (StringUtils.isBlank(originalFilename) || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf("."));
    }
}
