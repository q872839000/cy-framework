package com.cy.textsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分析配置类
 * <p>
 * 解决中文词汇配置读取问题，使用对象列表让每个词汇都有独立的词性配置
 * </p>
 *
 * @author pcy
 * @date 2025年12月19日
 */
@ConfigurationProperties(prefix = "cy.textsearch")
@Data
public class TextAnalysisProperties {

    /**
     * Lucene索引存储路径
     * 默认为当前目录下的Lucene-index文件夹（相对路径）
     */
    private String indexPath = "Lucene-index";

    /**
     * 自定义词汇列表
     * 配置格式：
     * cy:
     *   textsearch:
     *     custom-terms:
     *       - term: 领域资源库
     *         pos: nx
     *       - term: ISIM Cloud
     *         pos: eng
     *       - term: IOCM大模型
     *         pos: nx
     */
    private List<CustomTerm> customTerms = new ArrayList<>();


    /**
     * 自定义词汇配置项
     */
    @Data
    public static class CustomTerm {
        /**
         * 词汇
         */
        private String term;
        
        /**
         * 词性
         */
        private String pos;

    }
}
