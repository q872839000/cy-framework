package com.cy.filestore.config;

import com.cy.filestore.FileServiceFacade;
import com.cy.filestore.spi.FileStorageStrategyFactory;
import com.cy.filestore.storage.FileStorageStrategy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文件存储自动配置
 * <p>
 * 根据 {@code cy.filestore.type} 通过 SPI 工厂创建存储策略：
 * 内置 {@code local}（默认）与 {@code minio}（需引入 MinIO 客户端依赖），
 * 其他类型由 {@code FileStorageStrategyProvider} SPI 扩展提供。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
@AutoConfiguration
@EnableConfigurationProperties(FileStoreProperties.class)
public class FileStoreAutoConfiguration {

    /**
     * 创建文件存储策略
     *
     * @param properties 文件存储配置
     * @return 文件存储策略
     */
    @Bean
    @ConditionalOnMissingBean(FileStorageStrategy.class)
    public FileStorageStrategy fileStorageStrategy(FileStoreProperties properties) {
        return FileStorageStrategyFactory.create(properties.getType(), properties);
    }

    /**
     * 创建文件服务门面
     *
     * @param storageStrategy 文件存储策略
     * @return 文件服务门面
     */
    @Bean
    @ConditionalOnBean(FileStorageStrategy.class)
    @ConditionalOnMissingBean(FileServiceFacade.class)
    public FileServiceFacade fileServiceFacade(FileStorageStrategy storageStrategy) {
        return new FileServiceFacade(storageStrategy);
    }
}
