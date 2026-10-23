package com.cy.filestore;

import com.cy.filestore.resource.FileResource;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * 文件服务门面
 * <p>
 * 文件管理的统一入口，封装底层 {@link FileStorageStrategy} 的上传、下载、删除等能力。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class FileServiceFacade {

    private static final Logger log = LoggerFactory.getLogger(FileServiceFacade.class);

    /**
     * 文件存储策略
     */
    private final FileStorageStrategy storageStrategy;

    /**
     * 创建文件服务门面
     *
     * @param storageStrategy 文件存储策略
     */
    public FileServiceFacade(FileStorageStrategy storageStrategy) {
        this.storageStrategy = storageStrategy;
    }

    /**
     * 上传文件
     *
     * @param request 文件上传请求
     * @return 文件信息
     */
    public FileInfo upload(FileUploadRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("文件上传请求不能为空");
        }

        log.debug("开始上传文件: filename={}, size={}",
                request.getOriginalFilename(), request.getSize());

        return storageStrategy.upload(request);
    }

    /**
     * 下载文件（返回资源，自动管理关闭）
     * <p>
     * 推荐配合 try-with-resources 使用：
     * </p>
     * <pre>{@code
     * try (FileResource resource = facade.downloadAsResource(fileKey)) {
     *     InputStream stream = resource.getInputStream();
     *     // 使用流...
     * }
     * }</pre>
     *
     * @param fileKey 文件唯一标识
     * @return 文件资源（包含流和元数据）
     */
    public FileResource downloadAsResource(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        log.debug("开始下载文件资源: fileKey={}", fileKey);
        return storageStrategy.downloadAsResource(fileKey);
    }

    /**
     * 下载文件到本地路径
     *
     * @param fileKey    文件唯一标识
     * @param targetPath 目标路径
     */
    public void downloadToFile(String fileKey, Path targetPath) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("文件标识不能为空");
        }
        if (targetPath == null) {
            throw new IllegalArgumentException("目标路径不能为空");
        }

        log.debug("开始下载文件到本地: fileKey={}, targetPath={}", fileKey, targetPath);
        storageStrategy.downloadToFile(fileKey, targetPath);
    }

    /**
     * 获取文件元数据（不打开文件流）
     *
     * @param fileKey 文件唯一标识
     * @return 文件信息
     */
    public FileInfo stat(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        return storageStrategy.stat(fileKey);
    }

    /**
     * 删除文件
     *
     * @param fileKey 文件唯一标识
     * @return 是否删除成功
     */
    public boolean delete(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        log.debug("开始删除文件: fileKey={}", fileKey);
        return storageStrategy.delete(fileKey);
    }

    /**
     * 检查文件是否存在
     *
     * @param fileKey 文件唯一标识
     * @return 是否存在
     */
    public boolean exists(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return false;
        }

        return storageStrategy.exists(fileKey);
    }

    /**
     * 获取存储类型
     *
     * @return 存储类型（local/minio 或 SPI 扩展类型）
     */
    public String getStorageType() {
        return storageStrategy.getStorageType();
    }
}
