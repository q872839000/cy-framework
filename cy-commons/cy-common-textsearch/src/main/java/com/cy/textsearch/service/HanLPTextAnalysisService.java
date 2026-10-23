package com.cy.textsearch.service;

import com.cy.textsearch.core.ChineseTextProcessor;
import com.cy.textsearch.entity.TextAnalysisResult;
import com.hankcs.hanlp.HanLP;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * HanLP文本分析服务
 * <p>
 * 使用统一的ChineseTextProcessor进行文本处理：
 * 1. 消除重复的分词逻辑，避免维护多套代码
 * 2. 优化中文处理，移除英文标签依赖
 * 3. 支持自定义专有名词和领域词典
 * 4. 确保与索引分词的一致性
 * </p>
 *
 * @author pcy
 * @date 2025年12月19日（重构版）
 */
public class HanLPTextAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(HanLPTextAnalysisService.class);

    /**
     * 统一的中文文本处理器
     */
    private final ChineseTextProcessor textProcessor;

    /**
     * 构造函数
     *
     * @param textProcessor 中文文本处理器
     */
    public HanLPTextAnalysisService(ChineseTextProcessor textProcessor) {
        this.textProcessor = textProcessor;
        log.info("重构版HanLP文本分析服务初始化成功（使用统一ChineseTextProcessor）");
    }

    /**
     * 完整文本分析
     * <p>
     * 使用统一的ChineseTextProcessor进行分析，确保：
     * - 与索引分词的一致性
     * - 支持专有名词识别
     * - 优化的中文处理策略
     * </p>
     *
     * @param text 待分析文本
     * @return 完整的分析结果
     */
    public TextAnalysisResult analyze(String text) {
        if (text == null || text.trim().isEmpty()) {
            return createEmptyResult(text);
        }

        try {
            // 使用统一处理器进行详细分析
            ChineseTextProcessor.DetailedAnalysisResult detailedResult = textProcessor.analyzeDetailed(text);
            
            // 转换为服务层的数据结构
            List<TextAnalysisResult.TermWithPos> termsWithPos = detailedResult.getAllTerms().stream()
                    .map(term -> TextAnalysisResult.TermWithPos.builder()
                            .word(term.getWord())
                            .pos(term.getPos())
                            .offset(term.getStart())
                            .end(term.getEnd())
                            .build())
                    .collect(Collectors.toList());

            // 获取有效词项
            List<String> validTerms = detailedResult.getValidTerms();

            // 转换命名实体格式
            Map<String, List<TextAnalysisResult.NamedEntity>> namedEntities = convertNamedEntities(detailedResult.getNamedEntities());

            // 提取关键词（使用HanLP的TextRank算法）
            Map<String, Double> keywords = extractKeywords(text, 20);

            // 计算词频统计
            Map<String, Integer> termFrequency = validTerms.stream()
                    .collect(Collectors.toMap(
                            word -> word,
                            word -> 1,
                            Integer::sum
                    ));

            return TextAnalysisResult.builder()
                    .originalText(text)
                    .terms(termsWithPos)
                    .validTerms(validTerms)
                    .namedEntities(namedEntities)
                    .keywords(keywords)
                    .termFrequency(termFrequency)
                    .build();

        } catch (Exception e) {
            log.error("完整文本分析失败，使用降级策略: text={}", text, e);
            return analyzeTokenization(text);
        }
    }

    /**
     * 基础分词分析（降级处理）
     * <p>
     * 当完整分析失败时使用的降级策略，仅提供基础的分词结果
     * </p>
     *
     * @param text 待分析文本
     * @return 基础分词结果
     */
    public TextAnalysisResult analyzeTokenization(String text) {
        if (text == null || text.trim().isEmpty()) {
            return createEmptyResult(text);
        }

        try {
            // 使用统一处理器进行基础分词
            List<String> tokens = textProcessor.tokenize(text);
            
            // 构建简化的词项信息（没有精确位置信息）
            List<TextAnalysisResult.TermWithPos> termsWithPos = new ArrayList<>();
            int offset = 0;
            for (String token : tokens) {
                // 简化的位置计算
                int start = text.indexOf(token, offset);
                if (start >= 0) {
                    termsWithPos.add(TextAnalysisResult.TermWithPos.builder()
                            .word(token)
                            .pos("unknown") // 降级模式下不提供详细词性
                            .offset(start)
                            .end(start + token.length())
                            .build());
                    offset = start + token.length();
                }
            }

            // 计算词频统计
            Map<String, Integer> termFrequency = tokens.stream()
                    .collect(Collectors.toMap(
                            word -> word,
                            word -> 1,
                            Integer::sum
                    ));

            return TextAnalysisResult.builder()
                    .originalText(text)
                    .terms(termsWithPos)
                    .validTerms(tokens)
                    .namedEntities(Collections.emptyMap())
                    .keywords(Collections.emptyMap())
                    .termFrequency(termFrequency)
                    .build();

        } catch (Exception e) {
            log.error("基础分词分析失败: {}", text, e);
            return createEmptyResult(text);
        }
    }

    /**
     * 关键词提取
     * <p>
     * 使用HanLP的TextRank算法提取关键词，支持自定义数量
     * </p>
     *
     * @param text 待分析文本
     * @param topN 提取的关键词数量
     * @return 关键词及其权重映射
     */
    public Map<String, Double> extractKeywords(String text, int topN) {
        if (text == null || text.trim().isEmpty() || topN <= 0) {
            return Collections.emptyMap();
        }

        try {
            // 使用HanLP的TextRank关键词提取
            List<String> keywordList = HanLP.extractKeyword(text, Math.min(topN, 50)); // 限制最大数量

            // 计算关键词权重（基于排序位置递减）
            Map<String, Double> keywords = new LinkedHashMap<>();
            double baseWeight = 1.0;
            double decayRate = 0.03; // 降低衰减率，保持更多关键词的区分度
            
            for (int i = 0; i < keywordList.size(); i++) {
                String keyword = keywordList.get(i);
                if (keyword != null && !keyword.trim().isEmpty()) {
                    // 权重递减：第一个关键词权重最高，后续依次递减
                    double weight = baseWeight * (1.0 - i * decayRate);
                    keywords.put(keyword.trim(), Math.max(weight, 0.05)); // 最小权重0.05
                }
            }

            log.debug("关键词提取完成: 输入长度={}, 提取数量={}", text.length(), keywords.size());
            return keywords;
            
        } catch (Exception e) {
            log.warn("关键词提取失败，返回空结果: text_length={}, error={}", 
                    text.length(), e.getMessage());
            return Collections.emptyMap();
        }
    }


    /**
     * 创建空分析结果
     */
    private TextAnalysisResult createEmptyResult(String text) {
        return TextAnalysisResult.builder()
                .originalText(text)
                .terms(Collections.emptyList())
                .validTerms(Collections.emptyList())
                .namedEntities(Collections.emptyMap())
                .keywords(Collections.emptyMap())
                .termFrequency(Collections.emptyMap())
                .build();
    }

    /**
     * 转换命名实体格式
     * <p>
     * 将ChineseTextProcessor的实体格式转换为服务层格式
     * </p>
     */
    private Map<String, List<TextAnalysisResult.NamedEntity>> convertNamedEntities(
            Map<String, List<String>> processorEntities) {
        
        Map<String, List<TextAnalysisResult.NamedEntity>> result = new HashMap<>();
        
        for (Map.Entry<String, List<String>> entry : processorEntities.entrySet()) {
            String entityType = entry.getKey();
            List<String> entityTexts = entry.getValue();
            
            List<TextAnalysisResult.NamedEntity> entities = entityTexts.stream()
                    .map(text -> TextAnalysisResult.NamedEntity.builder()
                            .text(text)
                            .type(entityType)
                            .offset(0)  // ChineseTextProcessor暂不提供精确位置
                            .end(text.length())
                            .build())
                    .collect(Collectors.toList());
            
            result.put(entityType, entities);
        }
        
        return result;
    }
}

