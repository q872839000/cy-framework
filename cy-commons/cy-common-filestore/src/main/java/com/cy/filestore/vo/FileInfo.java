package com.cy.filestore.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 文件信息
 *
 * @author pcy
 * @date 2026/04/16
 */
@Data
@Builder
public class FileInfo {

    /**
     * 文件唯一标识（文件路径或对象键）
     */
    private String fileKey;

    /**
     * 文件大小（字节）
     */
    private long size;

    /**
     * 文件内容类型（MIME 类型）
     */
    private String contentType;

    /**
     * 原始文件名，仅上传时可用
     */
    private String originalFilename;

    /**
     * 额外的元数据
     */
    private Map<String, Object> metadata;
}
