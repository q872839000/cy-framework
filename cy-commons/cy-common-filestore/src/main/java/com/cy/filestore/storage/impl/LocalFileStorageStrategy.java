package com.cy.filestore.storage.impl;

import com.cy.core.exception.CYException;
import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.resource.FileResource;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.utils.FileKeyUtils;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;

/**
 * 本地文件存储策略实现
 * <p>
 * 将文件存储到本地文件系统，按照统一的 fileKey 规则组织目录结构。
 * 基于 {@link Files} API 的原子操作保证并发安全。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class LocalFileStorageStrategy implements FileStorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageStrategy.class);

    /**
     * 存储类型标识
     */
    public static final String STORAGE_TYPE = "local";

    /**
     * 默认内容类型
     */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * 本地存储配置
     */
    private final FileStoreProperties.LocalProperties localProperties;

    /**
     * 创建本地文件存储策略
     *
     * @param fileStoreProperties 文件存储配置
     */
    public LocalFileStorageStrategy(FileStoreProperties fileStoreProperties) {
        if (fileStoreProperties == null || fileStoreProperties.getLocal() == null
                || StringUtils.isBlank(fileStoreProperties.getLocal().getBasePath())) {
            throw new CYException(32008);
        }
        this.localProperties = fileStoreProperties.getLocal();
    }

    @Override
    public FileInfo upload(FileUploadRequest request) {
        if (request == null || request.getInputStream() == null) {
            throw new IllegalArgumentException("文件上传请求或输入流不能为空");
        }

        String fileKey = null;
        try {
            fileKey = FileKeyUtils.generateFileKey(request.getFileKey(), request.getOriginalFilename());
            Path filePath = resolveFilePath(fileKey);

            Path parentDir = filePath.getParent();
            if (parentDir != null) {
                Files.createDirectories(parentDir);
            }

            // CREATE_NEW 确保不覆盖已有文件
            try (InputStream is = request.getInputStream();
                 OutputStream os = Files.newOutputStream(filePath,
                         StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                is.transferTo(os);
            } catch (FileAlreadyExistsException e) {
                throw new CYException(32004, e, fileKey);
            }

            log.info("文件上传成功: fileKey={}, size={}", fileKey, request.getSize());

            return FileInfo.builder()
                    .fileKey(fileKey)
                    .size(request.getSize())
                    .contentType(request.getContentType())
                    .originalFilename(request.getOriginalFilename())
                    .metadata(request.getExtraMetadata() != null
                            ? new HashMap<>(request.getExtraMetadata()) : new HashMap<>())
                    .build();

        } catch (CYException e) {
            throw e;
        } catch (IOException e) {
            throw new CYException(32000, e, request.getOriginalFilename());
        }
    }

    @Override
    public FileResource downloadAsResource(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        try {
            Path filePath = resolveExistingFile(fileKey);

            long size = Files.size(filePath);
            String contentType = Files.probeContentType(filePath);
            if (contentType == null) {
                contentType = DEFAULT_CONTENT_TYPE;
            }

            InputStream inputStream = Files.newInputStream(filePath, StandardOpenOption.READ);

            return new FileResource(fileKey, inputStream, size, contentType);

        } catch (CYException e) {
            throw e;
        } catch (IOException e) {
            throw new CYException(32001, e, fileKey);
        }
    }

    @Override
    public void downloadToFile(String fileKey, Path targetPath) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }
        if (targetPath == null) {
            throw new IllegalArgumentException("目标路径不能为空");
        }

        try {
            Path sourcePath = resolveExistingFile(fileKey);

            Path targetDir = targetPath.getParent();
            if (targetDir != null) {
                Files.createDirectories(targetDir);
            }

            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.debug("文件下载到本地成功: fileKey={}, targetPath={}", fileKey, targetPath);

        } catch (CYException e) {
            throw e;
        } catch (IOException e) {
            throw new CYException(32001, e, fileKey);
        }
    }

    @Override
    public FileInfo stat(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        try {
            Path filePath = resolveExistingFile(fileKey);

            String contentType = Files.probeContentType(filePath);
            return FileInfo.builder()
                    .fileKey(fileKey)
                    .size(Files.size(filePath))
                    .contentType(contentType != null ? contentType : DEFAULT_CONTENT_TYPE)
                    .build();

        } catch (CYException e) {
            throw e;
        } catch (IOException e) {
            throw new CYException(32001, e, fileKey);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        try {
            Path filePath = resolveFilePath(fileKey);

            if (!Files.exists(filePath)) {
                log.warn("文件不存在，无需删除: fileKey={}", fileKey);
                return true;
            }

            if (!Files.isRegularFile(filePath)) {
                throw new CYException(32007, fileKey);
            }

            Files.delete(filePath);
            log.info("文件删除成功: fileKey={}", fileKey);
            return true;

        } catch (CYException e) {
            throw e;
        } catch (IOException e) {
            throw new CYException(32002, e, fileKey);
        }
    }

    @Override
    public boolean exists(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            return false;
        }

        try {
            Path filePath = resolveFilePath(fileKey);
            return Files.exists(filePath) && Files.isRegularFile(filePath);
        } catch (Exception e) {
            log.error("检查文件是否存在异常: fileKey={}", fileKey, e);
            return false;
        }
    }

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }

    /**
     * 解析 fileKey 对应的本地路径，并校验文件存在且为普通文件
     *
     * @param fileKey 文件键
     * @return 本地文件路径
     */
    private Path resolveExistingFile(String fileKey) {
        Path filePath = resolveFilePath(fileKey);
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            throw new CYException(32003, fileKey);
        }
        return filePath;
    }

    /**
     * 将 fileKey 解析为本地文件系统中的安全路径
     *
     * @param fileKey 文件键
     * @return 本地存储绝对路径
     */
    private Path resolveFilePath(String fileKey) {
        String normalizedFileKey = FileKeyUtils.normalizeRelativePath(fileKey);
        if (StringUtils.isBlank(normalizedFileKey)) {
            throw new IllegalArgumentException("文件标识不能为空: " + fileKey);
        }
        Path basePath = Paths.get(localProperties.getBasePath()).toAbsolutePath().normalize();
        Path targetPath = basePath.resolve(normalizedFileKey).normalize();
        if (!targetPath.startsWith(basePath)) {
            throw new IllegalArgumentException("文件标识超出本地存储根目录: " + fileKey);
        }
        return targetPath;
    }
}
