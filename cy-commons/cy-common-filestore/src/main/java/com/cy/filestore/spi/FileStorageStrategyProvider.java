package com.cy.filestore.spi;

import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.storage.FileStorageStrategy;

/**
 * 文件存储策略提供者 SPI
 * <p>
 * 通过 Java SPI 机制支持第三方存储实现扩展（如 OSS、S3、Azure Blob 等）。
 * </p>
 * <p>
 * 使用方式：
 * </p>
 * <ol>
 *   <li>实现此接口，创建自定义存储策略提供者</li>
 *   <li>在 {@code META-INF/services/com.cy.filestore.spi.FileStorageStrategyProvider} 文件中注册实现类</li>
 *   <li>配置 {@code cy.filestore.type} 为自定义类型名称</li>
 * </ol>
 * <p>
 * 示例：
 * </p>
 * <pre>{@code
 * public class OssStorageStrategyProvider implements FileStorageStrategyProvider {
 *     @Override
 *     public String getType() {
 *         return "oss";
 *     }
 *
 *     @Override
 *     public FileStorageStrategy create(FileStoreProperties properties) {
 *         return new OssFileStorageStrategy(properties);
 *     }
 * }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/16
 */
public interface FileStorageStrategyProvider {

    /**
     * 获取存储类型名称
     * <p>
     * 对应配置项 {@code cy.filestore.type} 的值。
     * </p>
     *
     * @return 存储类型（如 "oss", "s3", "azure"）
     */
    String getType();

    /**
     * 创建文件存储策略实例
     *
     * @param properties 文件存储配置
     * @return 文件存储策略实例
     */
    FileStorageStrategy create(FileStoreProperties properties);

    /**
     * 检查是否支持当前环境
     * <p>
     * 可选实现，默认返回 true。
     * 用于检查必要的依赖是否存在（如 SDK 类是否在 classpath 中）。
     * </p>
     *
     * @return 是否支持
     */
    default boolean isSupported() {
        return true;
    }
}
