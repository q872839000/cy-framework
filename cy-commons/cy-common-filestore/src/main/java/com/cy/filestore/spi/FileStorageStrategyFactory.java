package com.cy.filestore.spi;

import com.cy.core.exception.CYException;
import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.storage.FileStorageStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 文件存储策略工厂
 * <p>
 * 通过 SPI 机制加载所有可用的存储策略提供者，根据配置类型创建对应的存储策略实例。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public final class FileStorageStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(FileStorageStrategyFactory.class);

    /**
     * 已加载的存储策略提供者（key 为存储类型）
     */
    private static final Map<String, FileStorageStrategyProvider> PROVIDERS = new HashMap<>();

    static {
        loadProviders();
    }

    private FileStorageStrategyFactory() {
    }

    /**
     * 加载所有 SPI 提供者
     */
    private static void loadProviders() {
        ServiceLoader<FileStorageStrategyProvider> loader = ServiceLoader.load(FileStorageStrategyProvider.class);
        for (FileStorageStrategyProvider provider : loader) {
            if (provider.isSupported()) {
                String type = provider.getType();
                if (PROVIDERS.containsKey(type)) {
                    log.warn("存储策略提供者类型冲突，已忽略: type={}, provider={}",
                            type, provider.getClass().getName());
                } else {
                    PROVIDERS.put(type, provider);
                    log.info("加载存储策略提供者: type={}, provider={}",
                            type, provider.getClass().getName());
                }
            } else {
                log.debug("存储策略提供者不支持当前环境，已跳过: provider={}",
                        provider.getClass().getName());
            }
        }
    }

    /**
     * 根据类型创建存储策略
     *
     * @param type       存储类型
     * @param properties 配置属性
     * @return 存储策略实例
     */
    public static FileStorageStrategy create(String type, FileStoreProperties properties) {
        FileStorageStrategyProvider provider = PROVIDERS.get(type);
        if (provider == null) {
            throw new CYException(32005, type);
        }

        try {
            FileStorageStrategy strategy = provider.create(properties);
            log.info("创建存储策略成功: type={}, strategy={}",
                    type, strategy.getClass().getName());
            return strategy;
        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            throw new CYException(32005, e, type);
        }
    }

    /**
     * 检查是否支持指定类型
     *
     * @param type 存储类型
     * @return 是否支持
     */
    public static boolean isSupported(String type) {
        return PROVIDERS.containsKey(type);
    }

    /**
     * 获取所有支持的存储类型
     *
     * @return 存储类型与提供者的映射副本
     */
    public static Map<String, FileStorageStrategyProvider> getProviders() {
        return new HashMap<>(PROVIDERS);
    }
}
