package com.cy.system.file.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * @Auther: pcy
 * @Date: 2026/1/7
 * @Description: 文件查询请求
 */
@Data
@Schema(description = "文件查询请求")
public class FileQueryRequest {

    @Schema(description = "文件名称（模糊查询）")
    private String fileName;

    @Schema(description = "文件类型：1-普通文件，2-压缩包，3-图片，4-文档，5-视频，6-音频")
    private Integer fileType;

    @Schema(description = "文件状态：0-删除，1-正常，2-临时文件")
    private Integer status;

    @Schema(description = "页码")
    private Long pageNumber = 1L;

    @Schema(description = "每页大小")
    private Long pageSize = 10L;
}
