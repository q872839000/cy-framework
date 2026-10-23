package com.cy.filestore.vo;

import lombok.Builder;
import lombok.Data;

import java.io.InputStream;
import java.util.Map;

/**
 * 文件上传请求
 *
 * @author pcy
 * @date 2026/04/16
 */
@Data
@Builder
public class FileUploadRequest {

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件内容类型（MIME类型）
     */
    private String contentType;

    /**
     * 文件大小（字节）
     */
    private long size;

    /**
     * 文件输入流
     */
    private InputStream inputStream;

    /**
     * 业务目录或对象键前缀（可选）
     * <p>
     * 如果外部传入此字段，存储策略会将其视为业务目录或对象键前缀，
     * 并在其下生成最终文件键。
     * 如果为null或空，则使用系统自动生成的日期目录。
     * </p>
     */
    private String fileKey;

    /**
     * 额外的元数据
     */
    private Map<String, Object> extraMetadata;
}
