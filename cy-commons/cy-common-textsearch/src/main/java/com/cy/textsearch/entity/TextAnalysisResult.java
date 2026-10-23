package com.cy.textsearch.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 文本分析结果
 * <p>
 * 包含分词、词性标注、命名实体识别、关键词提取等完整的文本分析结果。
 * </p>
 *
 * @author pcy
 * @date 2025年12月17日
 */
@Data
@Builder
public class TextAnalysisResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 原始文本
     */
    private String originalText;

    /**
     * 分词结果（带词性标注）
     * 每个元素包含：词、词性、在文本中的位置
     */
    private List<TermWithPos> terms;

    /**
     * 有效词项列表（过滤停用词后）
     * 只保留名词、动词、实体词等有效词性
     */
    private List<String> validTerms;

    /**
     * 命名实体识别结果
     * key: 实体类型（人名、地名、机构名等）
     * value: 实体列表
     */
    private Map<String, List<NamedEntity>> namedEntities;

    /**
     * 关键词提取结果（TextRank算法）
     * key: 关键词
     * value: 权重值
     */
    private Map<String, Double> keywords;

    /**
     * 词频统计
     * key: 词
     * value: 出现次数
     */
    private Map<String, Integer> termFrequency;

    /**
     * 带词性的词项
     */
    @Data
    @Builder
    public static class TermWithPos implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 词
         */
        private String word;

        /**
         * 词性
         */
        private String pos;

        /**
         * 在文本中的起始位置
         */
        private Integer offset;

        /**
         * 在文本中的结束位置
         */
        private Integer end;
    }

    /**
     * 命名实体
     */
    @Data
    @Builder
    public static class NamedEntity implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 实体文本
         */
        private String text;

        /**
         * 实体类型（人名、地名、机构名等）
         */
        private String type;

        /**
         * 在文本中的起始位置
         */
        private Integer offset;

        /**
         * 在文本中的结束位置
         */
        private Integer end;
    }
}

