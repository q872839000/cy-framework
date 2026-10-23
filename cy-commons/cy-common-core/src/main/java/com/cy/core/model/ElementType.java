package com.cy.core.model;

/**
 * 文档元素类型枚举
 * <p>
 * 定义文档中不同的元素类型，用于结构化解析
 * </p>
 *
 * @author pcy
 * @since 2.0.0
 */
public enum ElementType {
    /**
     * 标题元素
     */
    TITLE,
    
    /**
     * 段落元素
     */
    PARAGRAPH,
    
    /**
     * 表格元素
     */
    TABLE,
    
    /**
     * 列表元素
     */
    LIST,
    
    /**
     * 图片元素
     */
    IMAGE,
    
    /**
     * 代码块元素
     */
    CODE,
    
    /**
     * 目录元素
     */
    TOC,
    
    /**
     * 其他元素
     */
    OTHER
}
