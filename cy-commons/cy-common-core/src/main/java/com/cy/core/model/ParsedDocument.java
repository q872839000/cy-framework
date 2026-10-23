package com.cy.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的文档信息
 * <p>
 * 合并了原来的 ParsedDocument 和 DocumentStructure，统一表示解析后的文档结构和元数据。
 * 包含文档元素列表、元数据、全文内容、目录等完整信息。
 * </p>
 *
 * @author pcy
 * @since 2.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedDocument {
    
    // ========== 基本信息 ==========
    
    /**
     * 原始文件名
     */
    private String originalFilename;
    
    /**
     * 文件内容类型（MIME type）
     */
    private String contentType;
    
    /**
     * 文档元数据（标题、作者、创建时间等）
     */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();
    
    /**
     * 解析出的全文内容（纯文本）
     */
    private String text;
    
    // ========== 结构化信息 ==========
    
    /**
     * 文档元素列表（按文档顺序）
     * <p>
     * 每个元素的 headingPath 字段已包含其标题路径信息
     * </p>
     */
    @Builder.Default
    private List<DocElement> elements = new ArrayList<>();
    
    /**
     * 目录元素列表（已从正文中分离）
     */
    @Builder.Default
    private List<DocElement> tocElements = new ArrayList<>();
    
    /**
     * 总页数
     */
    private Integer totalPages;
    
    // ========== 辅助方法 ==========
    
    /**
     * 添加文档元素
     */
    public void addElement(DocElement element) {
        if (this.elements == null) {
            this.elements = new ArrayList<>();
        }
        this.elements.add(element);
    }
    
    /**
     * 添加元数据
     */
    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
    
    /**
     * 获取所有标题元素
     */
    public List<DocElement> getHeadings() {
        List<DocElement> headings = new ArrayList<>();
        if (elements != null) {
            for (DocElement element : elements) {
                if (element.getType() == ElementType.TITLE) {
                    headings.add(element);
                }
            }
        }
        return headings;
    }
}
