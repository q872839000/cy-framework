package com.cy.system.file.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.cy.orm.core.domain.BaseEntity;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 系统文件实体
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Table(value = "sys_file")
@Schema(description = "系统文件")
public class SysFile extends BaseEntity {

    /**
     * 主键ID
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    @Schema(description = "主键ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 文件名称
     */
    @Schema(description = "文件名称")
    private String fileName;

    /**
     * 原始文件名
     */
    @Schema(description = "原始文件名")
    private String originalFileName;

    /**
     * 文件后缀
     */
    @Schema(description = "文件后缀")
    private String suffix;

    /**
     * 文件大小（字节）
     */
    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    /**
     * 文件存储路径
     */
    @Schema(description = "文件存储路径")
    private String filePath;

    /**
     * 文件MD5哈希值
     */
    @Schema(description = "文件MD5哈希值")
    private String md5Hash;

    /**
     * 是否已解压：0-未解压，1-已解压
     */
    @Schema(description = "是否已解压：0-未解压，1-已解压")
    private Integer unzipped;

    /**
     * 压缩包父文件ID（如果是解压文件）
     */
    @Schema(description = "压缩包父文件ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentFileId;


    /**
     * 文件备注
     */
    @Schema(description = "文件备注")
    private String remark;
}
