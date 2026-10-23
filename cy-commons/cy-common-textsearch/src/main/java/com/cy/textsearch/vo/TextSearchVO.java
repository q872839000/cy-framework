package com.cy.textsearch.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @description: 查询展示对象
 * @author: pcy
 * @create: 2025/12/17
 */
@Data
@Builder
public class TextSearchVO {

    /**
     * 文档Id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long docId;

    /**
     * 知识库ID
     */
    private String knowledgeId;

    /**
     * 文件Id
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fileId;

    /**
     * 文档名称
     */
    private String title;

    /**
     * 匹配分数
     * 文档与搜索关键词的相关性分数，范围0-1，越高越相关
     */
    private Double score;

    /**
     * 高亮内容
     * 包含高亮标签的文档内容片段
     */
    private String highlightedContent;

    /**
     * 元数据
     * 文档的额外信息，如文档类型、创建时间等
     */
    private Map<String, Object> metadata;

}
