package com.cy.filestore.spi.impl;

import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.spi.FileStorageStrategyProvider;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.storage.impl.LocalFileStorageStrategy;

/**
 * 本地文件存储策略提供者
 *
 * @author pcy
 * @date 2026/04/16
 */
public class LocalFileStorageStrategyProvider implements FileStorageStrategyProvider {

    @Override
    public String getType() {
        return LocalFileStorageStrategy.STORAGE_TYPE;
    }

    @Override
    public FileStorageStrategy create(FileStoreProperties properties) {
        return new LocalFileStorageStrategy(properties);
    }

    @Override
    public boolean isSupported() {
        return true;
    }
}
