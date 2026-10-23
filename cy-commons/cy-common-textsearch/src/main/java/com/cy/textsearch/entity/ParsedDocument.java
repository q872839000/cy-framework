package com.cy.textsearch.entity;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 解析后的文档信息
 *
 * @author pcy
 * @since 1.0.0
 */
@Data
@Builder
public class ParsedDocument {

    /**
     * 解析出的文本内容
     */
    private String text;

    /**
     * 文档元数据（标题、作者、创建时间等）
     */
    private Map<String, String> metadata;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件内容类型
     */
    private String contentType;
}

