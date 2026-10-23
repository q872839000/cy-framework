package com.cy.core.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 文档元素
 * <p>
 * 表示文档中的一个结构化元素（标题、段落、表格等），包含类型、内容、层级等信息
 * </p>
 *
 * @author pcy
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocElement {
    
    /**
     * 元素类型
     */
    private ElementType type;
    
    /**
     * 元素内容
     */
    private String content;
    
    /**
     * 标题层级（仅对TITLE类型有效，1-6表示h1-h6）
     */
    private Integer level;
    
    /**
     * 页码（从1开始，可选）
     */
    private Integer pageNumber;
    
    /**
     * 在原文中的起始位置
     */
    private Integer startOffset;
    
    /**
     * 在原文中的结束位置
     */
    private Integer endOffset;
    
    /**
     * 元素序号（在文档中的顺序，从0开始）
     */
    private Integer index;

    /**
     * 与前一切片重叠字符数
     */
    @Schema(description = "与前一切片重叠字符数")
    private Integer overlapPrev;

    /**
     * 与后一切片重叠字符数
     */
    @Schema(description = "与后一切片重叠字符数")
    private Integer overlapNext;
    
    /**
     * 元数据（样式、字体、是否加粗等附加信息）
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();
    
    /**
     * 是否为目录区域的内容
     */
    @Builder.Default
    private Boolean isToc = false;
    
    /**
     * 标题路径（从根标题到当前元素所在章节的完整路径）
     * <p>
     * 例如："第1章/1.2节/1.2.3小节"<br>
     * 用于分块时提供上下文信息，避免构建复杂的标题树
     * </p>
     */
    private String headingPath;
    
    /**
     * 添加元数据
     */
    public void addMetadata(String key, Object value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
    
    /**
     * 获取元数据
     */
    public Object getMetadata(String key) {
        return this.metadata != null ? this.metadata.get(key) : null;
    }
}
