package com.cy.system.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 文件上传请求
 */
@Data
@Schema(description = "文件上传请求")
public class FileUploadRequest {

    @Schema(description = "文件名称")
    @NotBlank(message = "文件名称不能为空")
    private String fileName;

    @Schema(description = "原始文件名")
    private String originalFileName;

    @Schema(description = "文件后缀")
    private String suffix;

    @Schema(description = "文件大小")
    private Long fileSize;

    @Schema(description = "文件MD5")
    private String md5Hash;

    @Schema(description = "文件类型")
    private Integer fileType;

    @Schema(description = "存储桶名称")
    private String bucketName;

    @Schema(description = "对象存储键值")
    private String objectKey;

    @Schema(description = "备注")
    private String remark;
}
