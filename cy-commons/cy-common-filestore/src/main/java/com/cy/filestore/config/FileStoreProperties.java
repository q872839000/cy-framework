package com.cy.filestore.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置属性
 *
 * @author pcy
 * @date 2026/04/16
 */
@Data
@ConfigurationProperties(prefix = "cy.filestore")
public class FileStoreProperties {

    /**
     * 存储类型
     * <p>
     * 内置支持 {@code local}（本地文件系统）和 {@code minio}（MinIO 对象存储），
     * 其他类型可通过 SPI 扩展。未配置时默认 {@code local}。
     * </p>
     */
    private String type = "local";

    /**
     * 本地文件存储配置
     */
    private LocalProperties local = new LocalProperties();

    /**
     * MinIO 对象存储配置
     */
    private MinioProperties minio = new MinioProperties();

    /**
     * 本地文件存储配置
     */
    @Data
    public static class LocalProperties {

        /**
         * 文件存储基础路径
         */
        private String basePath = "./data/files";
    }

    /**
     * MinIO 对象存储配置
     */
    @Data
    public static class MinioProperties {

        /**
         * MinIO 服务地址
         * <p>
         * 支持带协议的完整地址（如 {@code http://127.0.0.1:9000}）；
         * 未包含协议时由 {@link #secure} 决定使用 HTTP 还是 HTTPS。
         * </p>
         */
        private String url;

        /**
         * 访问密钥（Access Key）
         */
        private String accessKey;

        /**
         * 秘密密钥（Secret Key）
         */
        private String secretKey;

        /**
         * 存储桶名称（Bucket Name）
         */
        private String bucketName;

        /**
         * 是否使用 HTTPS，仅当 {@link #url} 未包含协议头时生效
         */
        private boolean secure = false;
    }
}
