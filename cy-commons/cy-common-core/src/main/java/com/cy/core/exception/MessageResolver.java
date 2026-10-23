package com.cy.core.exception;

import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;

/**
 * 消息解析器
 * 基于ResourceBundleMessageSource实现错误消息的国际化加载
 * 支持多个properties文件加载，当前模块优先级最高
 *
 * @author pcy
 * @date 2026/1/7
 */
public class MessageResolver {

    private static final Logger log = LoggerFactory.getLogger(MessageResolver.class);
    private static final String DEFAULT_MESSAGE_TEMPLATE = "Unknown error [{0}]";
    private static ResourceBundleMessageSource messageSource;
    private final PathMatchingResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();

    /**
     * 初始化消息解析器
     */
    public void initialize() {
        messageSource = new ResourceBundleMessageSource();
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        addErrorMessageBasenames(messageSource);
    }

    /**
     * 扫描并添加所有error-messages开头的properties文件
     */
    private void addErrorMessageBasenames(ResourceBundleMessageSource bundleMessageSource) {
        try {
            // 扫描所有error-messages开头的properties文件
            Resource[] resources = resourceResolver.getResources("classpath*:error-messages*.properties");
            
            // 使用LinkedHashSet保证顺序，同时去重
            Set<String> basenames = new LinkedHashSet<>();
            

            // 分两组：jar包资源（依赖模块）和文件系统资源（当前模块）
            List<String> dependencyBasenames = new ArrayList<>();
            List<String> currentModuleBasenames = new ArrayList<>();
            
            for (Resource resource : resources) {
                String path = resource.getURI().toString();
                String basename = extractBasename(path);
                
                if (basename != null) {
                    boolean isFromJar = path.contains(".jar!");
                    if (isFromJar) {
                        dependencyBasenames.add(basename);
                    } else {
                        currentModuleBasenames.add(basename);
                    }
                }
            }
            
            // 先添加依赖模块的basenames，再添加当前模块的（实现覆盖）
            basenames.addAll(dependencyBasenames);
            basenames.addAll(currentModuleBasenames);
            
            // 批量添加到messageSource
            String[] basenameArray = basenames.toArray(new String[0]);
            bundleMessageSource.setBasenames(basenameArray);
            
        } catch (IOException e) {
            log.error("Failed to scan error message files: {}", e.getMessage(), e);
        }
    }

    /**
     * 从路径中提取basename
     * 例如: /path/to/error-messages-system.properties -> error-messages-system
     */
    private String extractBasename(String path) {
        try {
            // 获取文件名部分
            int lastSlashIndex = path.lastIndexOf("/");
            String fileName = path.substring(lastSlashIndex + 1);
            
            // 移除.properties扩展名
            if (fileName.endsWith(".properties")) {
                return fileName.substring(0, fileName.lastIndexOf(".properties"));
            }
        } catch (Exception e) {
            log.warn("Failed to extract basename from: {}", path);
        }
        return null;
    }

    /**
     * 根据错误码获取消息
     *
     * @param errorCode 错误码
     * @param args      参数数组
     * @return 格式化后的消息
     */
    public String formatMessage(Integer errorCode, Object... args) {
        if (messageSource == null) {
            return MessageFormat.format(DEFAULT_MESSAGE_TEMPLATE, errorCode);
        }
        
        try {
            return messageSource.getMessage(String.valueOf(errorCode), args, Locale.getDefault());
        } catch (NoSuchMessageException e) {
            // 如果找不到对应的错误码，返回默认消息
            return MessageFormat.format(DEFAULT_MESSAGE_TEMPLATE, errorCode);
        }
    }

    /**
     * 根据错误码获取消息模板（不格式化）
     *
     * @param errorCode 错误码
     * @return 消息模板
     */
    public String getMessage(Integer errorCode) {
        if (messageSource == null) {
            return DEFAULT_MESSAGE_TEMPLATE;
        }
        
        try {
            return messageSource.getMessage(String.valueOf(errorCode), null, Locale.getDefault());
        } catch (NoSuchMessageException e) {
            return DEFAULT_MESSAGE_TEMPLATE;
        }
    }
}
