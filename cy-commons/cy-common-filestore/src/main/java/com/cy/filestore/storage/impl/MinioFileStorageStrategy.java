package com.cy.filestore.storage.impl;

import com.cy.core.exception.CYException;
import com.cy.filestore.config.FileStoreProperties;
import com.cy.filestore.resource.FileResource;
import com.cy.filestore.storage.FileStorageStrategy;
import com.cy.filestore.utils.FileKeyUtils;
import com.cy.filestore.vo.FileInfo;
import com.cy.filestore.vo.FileUploadRequest;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * MinIO 文件存储策略实现
 * <p>
 * 将文件存储到 MinIO 对象存储服务。{@link MinioClient} 线程安全，可全局共享。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MinioFileStorageStrategy implements FileStorageStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageStrategy.class);

    /**
     * 存储类型标识
     */
    public static final String STORAGE_TYPE = "minio";

    /**
     * 默认内容类型
     */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /**
     * 分片大小：10MB（MinIO 推荐值）
     */
    private static final long PART_SIZE = 10 * 1024 * 1024L;

    /**
     * MinIO 服务端错误码：对象不存在
     */
    private static final String ERROR_NO_SUCH_KEY = "NoSuchKey";

    /**
     * MinIO 服务端错误码：对象不存在（部分版本返回）
     */
    private static final String ERROR_NO_SUCH_OBJECT = "NoSuchObject";

    /**
     * MinIO 服务端错误码：存储桶不存在
     */
    private static final String ERROR_NO_SUCH_BUCKET = "NoSuchBucket";

    /**
     * MinIO 服务端错误码：存储桶已被当前账号创建
     */
    private static final String ERROR_BUCKET_ALREADY_OWNED = "BucketAlreadyOwnedByYou";

    /**
     * MinIO 服务端错误码：存储桶已存在
     */
    private static final String ERROR_BUCKET_ALREADY_EXISTS = "BucketAlreadyExists";

    /**
     * {@link FileInfo#getMetadata()} 内置键：存储桶名称
     */
    private static final String META_BUCKET_NAME = "bucketName";

    /**
     * {@link FileInfo#getMetadata()} 内置键：存储类型
     */
    private static final String META_STORAGE_TYPE = "storageType";

    /**
     * HTTP 协议前缀
     */
    private static final String HTTP_PREFIX = "http://";

    /**
     * HTTPS 协议前缀
     */
    private static final String HTTPS_PREFIX = "https://";

    /**
     * MinIO 客户端
     */
    private final MinioClient minioClient;

    /**
     * MinIO 存储配置
     */
    private final FileStoreProperties.MinioProperties minioProperties;

    /**
     * 创建 MinIO 文件存储策略
     *
     * @param fileStoreProperties 文件存储配置
     */
    public MinioFileStorageStrategy(FileStoreProperties fileStoreProperties) {
        this.minioProperties = validateMinioProperties(fileStoreProperties);
        String endpoint = normalizeEndpoint(minioProperties.getUrl(), minioProperties.isSecure());
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
        log.info("初始化MinIO文件存储策略: bucketName={}, endpoint={}",
                minioProperties.getBucketName(), endpoint);
    }

    @Override
    public FileInfo upload(FileUploadRequest request) {
        if (request == null || request.getInputStream() == null) {
            throw new IllegalArgumentException("文件上传请求或输入流不能为空");
        }

        try {
            String fileKey = FileKeyUtils.generateFileKey(request.getFileKey(), request.getOriginalFilename());

            try (InputStream inputStream = request.getInputStream()) {
                ensureBucketExists();

                // 大小未知时传 -1，由 MinIO 按 partSize 分片上传
                long objectSize = request.getSize() > 0 ? request.getSize() : -1;

                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(minioProperties.getBucketName())
                        .object(fileKey)
                        .stream(inputStream, objectSize, PART_SIZE)
                        .contentType(StringUtils.defaultIfBlank(request.getContentType(), DEFAULT_CONTENT_TYPE))
                        .build());

                log.info("MinIO文件上传成功: bucketName={}, fileKey={}, size={}",
                        minioProperties.getBucketName(), fileKey, request.getSize());

                return buildUploadResult(request, fileKey);
            }
        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            throw new CYException(32000, e, request.getOriginalFilename());
        }
    }

    @Override
    public FileResource downloadAsResource(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        String normalizedFileKey = fileKey;
        try {
            normalizedFileKey = resolveObjectKey(fileKey);
            if (!bucketExists()) {
                throw new CYException(32003, fileKey);
            }

            StatObjectResponse stat = statObject(normalizedFileKey);

            GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(normalizedFileKey)
                    .build());

            return new FileResource(
                    fileKey,
                    response,
                    stat.size(),
                    stat.contentType() != null ? stat.contentType() : DEFAULT_CONTENT_TYPE
            );

        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            throw translateReadError(e, normalizedFileKey);
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

        try (FileResource resource = downloadAsResource(fileKey)) {
            Path targetDir = targetPath.getParent();
            if (targetDir != null) {
                Files.createDirectories(targetDir);
            }

            try (InputStream is = resource.getInputStream();
                 OutputStream os = Files.newOutputStream(targetPath,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                is.transferTo(os);
            }

            log.debug("MinIO文件下载到本地成功: fileKey={}, targetPath={}", fileKey, targetPath);

        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            throw new CYException(32001, e, fileKey);
        }
    }

    @Override
    public FileInfo stat(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        String normalizedFileKey = fileKey;
        try {
            normalizedFileKey = resolveObjectKey(fileKey);
            if (!bucketExists()) {
                throw new CYException(32003, fileKey);
            }

            StatObjectResponse stat = statObject(normalizedFileKey);
            return FileInfo.builder()
                    .fileKey(fileKey)
                    .size(stat.size())
                    .contentType(stat.contentType() != null ? stat.contentType() : DEFAULT_CONTENT_TYPE)
                    .build();

        } catch (CYException e) {
            throw e;
        } catch (Exception e) {
            throw translateReadError(e, normalizedFileKey);
        }
    }

    @Override
    public boolean delete(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            throw new IllegalArgumentException("文件标识不能为空");
        }

        String normalizedFileKey = fileKey;
        try {
            normalizedFileKey = resolveObjectKey(fileKey);
            if (!bucketExists()) {
                log.warn("MinIO存储桶不存在，无需删除文件: bucketName={}, fileKey={}",
                        minioProperties.getBucketName(), normalizedFileKey);
                return true;
            }

            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .object(normalizedFileKey)
                    .build());

            log.info("MinIO文件删除成功: bucketName={}, fileKey={}",
                    minioProperties.getBucketName(), normalizedFileKey);
            return true;

        } catch (ErrorResponseException e) {
            if (isObjectMissing(e) || isBucketMissing(e)) {
                log.warn("MinIO文件不存在，无需删除: bucketName={}, fileKey={}",
                        minioProperties.getBucketName(), normalizedFileKey);
                return true;
            }
            throw new CYException(32002, e, normalizedFileKey);
        } catch (Exception e) {
            throw new CYException(32002, e, normalizedFileKey);
        }
    }

    @Override
    public boolean exists(String fileKey) {
        if (StringUtils.isBlank(fileKey)) {
            return false;
        }

        String normalizedFileKey = fileKey;
        try {
            normalizedFileKey = resolveObjectKey(fileKey);
            if (!bucketExists()) {
                return false;
            }

            statObject(normalizedFileKey);
            return true;

        } catch (ErrorResponseException e) {
            if (isObjectMissing(e) || isBucketMissing(e)) {
                return false;
            }
            log.error("MinIO文件存在性检查失败: bucketName={}, fileKey={}",
                    minioProperties.getBucketName(), normalizedFileKey, e);
            return false;
        } catch (Exception e) {
            log.error("MinIO文件存在性检查失败: bucketName={}, fileKey={}",
                    minioProperties.getBucketName(), normalizedFileKey, e);
            return false;
        }
    }

    @Override
    public String getStorageType() {
        return STORAGE_TYPE;
    }

    /**
     * 组装上传成功后的文件信息
     *
     * @param request 上传请求
     * @param fileKey 文件键
     * @return 文件信息
     */
    private FileInfo buildUploadResult(FileUploadRequest request, String fileKey) {
        Map<String, Object> metadata = new HashMap<>();
        if (request.getExtraMetadata() != null) {
            metadata.putAll(request.getExtraMetadata());
        }
        metadata.put(META_BUCKET_NAME, minioProperties.getBucketName());
        metadata.put(META_STORAGE_TYPE, getStorageType());

        return FileInfo.builder()
                .fileKey(fileKey)
                .size(request.getSize())
                .contentType(request.getContentType())
                .originalFilename(request.getOriginalFilename())
                .metadata(metadata)
                .build();
    }

    /**
     * 将读取类异常转换为统一的 {@link CYException}：对象或存储桶缺失映射为 32003，其余映射为 32001
     *
     * @param e       原始异常
     * @param fileKey 文件键
     * @return 统一异常
     */
    private CYException translateReadError(Exception e, String fileKey) {
        if (e instanceof ErrorResponseException errorResponse
                && (isObjectMissing(errorResponse) || isBucketMissing(errorResponse))) {
            return new CYException(32003, e, fileKey);
        }
        return new CYException(32001, e, fileKey);
    }

    /**
     * 查询对象元数据
     *
     * @param objectKey 对象键
     * @return 对象元数据
     */
    private StatObjectResponse statObject(String objectKey) throws Exception {
        return minioClient.statObject(StatObjectArgs.builder()
                .bucket(minioProperties.getBucketName())
                .object(objectKey)
                .build());
    }

    /**
     * 校验并返回 MinIO 配置
     *
     * @param fileStoreProperties 文件存储配置
     * @return MinIO 配置
     */
    private FileStoreProperties.MinioProperties validateMinioProperties(FileStoreProperties fileStoreProperties) {
        if (fileStoreProperties == null || fileStoreProperties.getMinio() == null) {
            throw new CYException(32006);
        }
        FileStoreProperties.MinioProperties properties = fileStoreProperties.getMinio();
        if (StringUtils.isAnyBlank(properties.getUrl(), properties.getAccessKey(),
                properties.getSecretKey(), properties.getBucketName())) {
            throw new CYException(32006);
        }
        return properties;
    }

    /**
     * 规范化 MinIO 服务地址
     *
     * @param url    原始地址
     * @param secure 是否启用 HTTPS
     * @return 标准化后的 endpoint
     */
    private String normalizeEndpoint(String url, boolean secure) {
        String endpoint = StringUtils.removeEnd(StringUtils.trim(url), "/");
        if (endpoint.startsWith(HTTP_PREFIX) || endpoint.startsWith(HTTPS_PREFIX)) {
            return endpoint;
        }
        return (secure ? HTTPS_PREFIX : HTTP_PREFIX) + endpoint;
    }

    /**
     * 确保存储桶存在，不存在时自动创建
     */
    private void ensureBucketExists() throws Exception {
        if (bucketExists()) {
            return;
        }
        try {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(minioProperties.getBucketName())
                    .build());
            log.info("MinIO存储桶不存在，已自动创建: bucketName={}", minioProperties.getBucketName());
        } catch (ErrorResponseException e) {
            if (isBucketAlreadyExists(e)) {
                log.debug("MinIO存储桶已存在，忽略重复创建: bucketName={}", minioProperties.getBucketName());
                return;
            }
            throw e;
        }
    }

    /**
     * 检查 MinIO 存储桶是否存在
     */
    private boolean bucketExists() throws Exception {
        return minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(minioProperties.getBucketName())
                .build());
    }

    /**
     * 解析并校验对象键
     *
     * @param fileKey 文件键
     * @return 规范化后的对象键
     */
    private String resolveObjectKey(String fileKey) {
        String normalizedFileKey = FileKeyUtils.normalizeRelativePath(fileKey);
        if (StringUtils.isBlank(normalizedFileKey)) {
            throw new IllegalArgumentException("文件标识不能为空: " + fileKey);
        }
        return normalizedFileKey;
    }

    /**
     * 判断异常是否表示对象不存在
     */
    private boolean isObjectMissing(ErrorResponseException exception) {
        String code = exception.errorResponse() != null ? exception.errorResponse().code() : null;
        return ERROR_NO_SUCH_KEY.equals(code) || ERROR_NO_SUCH_OBJECT.equals(code);
    }

    /**
     * 判断异常是否表示存储桶不存在
     */
    private boolean isBucketMissing(ErrorResponseException exception) {
        String code = exception.errorResponse() != null ? exception.errorResponse().code() : null;
        return ERROR_NO_SUCH_BUCKET.equals(code);
    }

    /**
     * 判断异常是否表示存储桶已存在
     */
    private boolean isBucketAlreadyExists(ErrorResponseException exception) {
        String code = exception.errorResponse() != null ? exception.errorResponse().code() : null;
        return ERROR_BUCKET_ALREADY_OWNED.equals(code) || ERROR_BUCKET_ALREADY_EXISTS.equals(code);
    }
}
