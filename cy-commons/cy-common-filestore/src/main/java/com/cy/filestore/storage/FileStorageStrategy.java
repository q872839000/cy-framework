package com.cy.filestore.storage;

import com.cy.filestore.resource.FileResource;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;

import java.nio.file.Path;

/**
 * 文件存储策略接口
 * <p>
 * 定义文件存储的统一接口，支持不同的存储实现（本地文件系统、MinIO 等）。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public interface FileStorageStrategy {

    /**
     * 上传文件
     *
     * @param request 文件上传请求
     * @return 文件信息（包含文件键、大小、类型等）
     */
    FileInfo upload(FileUploadRequest request);

    /**
     * 下载文件（返回资源，自动管理关闭）
     * <p>
     * 推荐配合 try-with-resources 使用：
     * </p>
     * <pre>{@code
     * try (FileResource resource = storageStrategy.downloadAsResource(fileKey)) {
     *     InputStream stream = resource.getInputStream();
     *     // 使用流...
     * }
     * }</pre>
     *
     * @param fileKey 文件唯一标识
     * @return 文件资源（包含流和元数据）
     */
    FileResource downloadAsResource(String fileKey);

    /**
     * 下载文件到本地路径
     * <p>
     * 自动管理资源，无需调用方关闭流。
     * </p>
     *
     * @param fileKey    文件唯一标识
     * @param targetPath 目标路径
     */
    void downloadToFile(String fileKey, Path targetPath);

    /**
     * 获取文件元数据（不打开文件流）
     *
     * @param fileKey 文件唯一标识
     * @return 文件信息
     */
    FileInfo stat(String fileKey);

    /**
     * 删除文件
     *
     * @param fileKey 文件唯一标识
     * @return 是否删除成功
     */
    boolean delete(String fileKey);

    /**
     * 检查文件是否存在
     *
     * @param fileKey 文件唯一标识
     * @return 是否存在
     */
    boolean exists(String fileKey);

    /**
     * 获取存储类型
     *
     * @return 存储类型（local/minio 或 SPI 扩展类型）
     */
    String getStorageType();
}
