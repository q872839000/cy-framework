package com.cy.filestore.spi.impl;

import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.spi.FileStorageStrategyProvider;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.storage.impl.MinioFileStorageStrategy;

/**
 * MinIO 文件存储策略提供者
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MinioFileStorageStrategyProvider implements FileStorageStrategyProvider {

    @Override
    public String getType() {
        return MinioFileStorageStrategy.STORAGE_TYPE;
    }

    @Override
    public FileStorageStrategy create(FileStoreProperties properties) {
        return new MinioFileStorageStrategy(properties);
    }

    @Override
    public boolean isSupported() {
        // 检查 MinIO 客户端是否在 classpath 中
        try {
            Class.forName("io.minio.MinioClient");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
