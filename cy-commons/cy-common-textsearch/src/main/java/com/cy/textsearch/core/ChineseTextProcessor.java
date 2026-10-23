package com.cy.textsearch.core;

import com.cy.textsearch.config.TextAnalysisProperties;
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.dictionary.CustomDictionary;
import com.hankcs.hanlp.seg.Segment;
import com.hankcs.hanlp.seg.common.Term;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.cy.textsearch.core.ChineseTextConstants.*;

/**
 * 文本分析处理器
 * <p>
 * 统一的文本分析核心组件，支持中英文混合处理：
 * 1. 消除HanLPTextAnalysisService和HanLPAnalyzer的重复逻辑
 * 2. 优化中文词性标签处理，同时支持英文术语
 * 3. 支持专有名词和自定义词典
 * 4. 直接从application.yml读取配置，适配Spring Boot 2.2.2
 * </p>
 *
 * @author pcy
 * @date 2025年12月19日
 */
public class ChineseTextProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChineseTextProcessor.class);

    /**
     * 文本分析配置
     */
    private TextAnalysisProperties textAnalysisProperties;

    /**
     * 设置文本分析配置并加载自定义词汇
     *
     * @param textAnalysisProperties 文本分析配置
     */
    public void setTextAnalysisProperties(TextAnalysisProperties textAnalysisProperties) {
        this.textAnalysisProperties = textAnalysisProperties;
        // 加载自定义词汇到HanLP词典
        loadCustomTermsFromConfig();
    }

    /**
     * 自定义词汇字典（统一管理）
     * 格式：词汇 -> 词性
     */
    private final Map<String, String> customTerms = new LinkedHashMap<>();


    /**
     * HanLP分词器实例（线程安全）
     */
    private final ThreadLocal<Segment> segmentThreadLocal = ThreadLocal.withInitial(this::createSegment);

    /**
     * 从配置中动态加载自定义词汇并同步到HanLP词典
     * <p>
     * 使用新的TextAnalysisProperties配置类，支持每个词汇独立词性
     * </p>
     */
    private void loadCustomTermsFromConfig() {
        if (textAnalysisProperties == null) {
            log.debug("TextAnalysisProperties未配置，跳过自定义词汇加载");
            return;
        }

        List<TextAnalysisProperties.CustomTerm> configTerms = textAnalysisProperties.getCustomTerms();
        if (configTerms == null || configTerms.isEmpty()) {
            log.info("未配置自定义词汇(cy.textsearch.custom-terms)");
            return;
        }

        log.info("开始加载 {} 个自定义词汇...", configTerms.size());
        int successCount = 0;
        for (TextAnalysisProperties.CustomTerm customTerm : configTerms) {
            String term = customTerm.getTerm();
            String pos = customTerm.getPos();
            if (term != null && pos != null) {
                String cleanTerm = term.trim();
                String cleanPos = pos.trim();
                customTerms.put(cleanTerm, cleanPos);
                // 同步到HanLP词典
                try {
                    CustomDictionary.add(cleanTerm, cleanPos + CUSTOM_TERM_WEIGHT);
                    successCount++;
                    log.debug("词汇同步成功: {} -> {}", cleanTerm, cleanPos);
                } catch (Exception e) {
                    log.error("词汇同步失败: {} -> {}, 错误: {}", cleanTerm, cleanPos, e.getMessage());
                }
            }
        }
        log.info("自定义词汇加载完成: 成功 {} 个", successCount);
    }

    /**
     * 基础分词（用于Lucene索引和查询）
     * <p>
     * 提供最轻量级的分词结果，专门用于搜索场景：
     * - 只返回词汇列表，不包含词性和位置信息
     * - 过滤停用词和无效词汇
     * - 保证索引和查询时的一致性
     * </p>
     *
     * @param text 待分词文本
     * @return 分词结果列表
     */
    public List<String> tokenize(String text) {
        if (StringUtils.isBlank(text)) {
            return Collections.emptyList();
        }

        try {
            // 获取线程本地的分词器
            Segment segment = segmentThreadLocal.get();
            List<Term> terms = segment.seg(text);
            
            // 调试日志：显示原始分词结果
            if (log.isDebugEnabled()) {
                log.debug("分词原始结果 [{}]:", text);
                terms.forEach(term -> {
                    String pos = term.nature != null ? term.nature.toString() : "unknown";
                    boolean isCustom = customTerms.containsKey(term.word);
                    log.debug("  - {} [{}] {}", term.word, pos, isCustom ? "★自定义" : "");
                });
            }

            // 过滤并返回有效词汇
            List<String> result = terms.stream()
                    .filter(term -> {
                        String pos = term.nature != null ? term.nature.toString() : "";
                        boolean valid = isValidSearchTerm(term.word, pos);
                        
                        // 调试日志：显示过滤逻辑
                        if (log.isDebugEnabled() && !valid) {
                            log.debug("  × 过滤掉: {} [{}]", term.word, pos);
                        }
                        return valid;
                    })
                    .map(term -> term.word)
                    .collect(Collectors.toList());
            
            // 调试日志：显示最终结果
            if (log.isDebugEnabled()) {
                log.debug("分词最终结果: {}", result);
                log.debug("自定义词汇命中: {}", 
                    result.stream().filter(customTerms::containsKey).collect(Collectors.toList()));
            }
            
            return result;
                    
        } catch (Exception e) {
            log.warn("分词处理异常，使用降级策略: text={}, error={}", text, e.getMessage());
            return fallbackTokenize(text);
        }
    }

    /**
     * 详细分析（用于文本分析服务）
     * <p>
     * 提供完整的文本分析结果：
     * - 包含词汇、词性、位置信息
     * - 支持命名实体识别
     * - 用于详细的文本处理需求
     * </p>
     *
     * @param text 待分析文本
     * @return 详细分析结果
     */
    public DetailedAnalysisResult analyzeDetailed(String text) {
        if (StringUtils.isBlank(text)) {
            return new DetailedAnalysisResult(text, Collections.emptyList(), Collections.emptyList(), Collections.emptyMap());
        }

        try {
            Segment segment = segmentThreadLocal.get();
            List<Term> terms = segment.seg(text);

            // 转换为详细词项信息
            List<DetailedTerm> detailedTerms = terms.stream()
                    .map(term -> new DetailedTerm(
                            term.word,
                            term.nature != null ? term.nature.toString() : "",
                            term.offset,
                            term.offset + term.word.length()
                    ))
                    .collect(Collectors.toList());

            // 提取有效词汇
            List<String> validTerms = terms.stream()
                    .filter(term -> isValidSearchTerm(term.word, term.nature != null ? term.nature.toString() : ""))
                    .map(term -> term.word)
                    .collect(Collectors.toList());

            // 提取命名实体
            Map<String, List<String>> namedEntities = extractNamedEntities(terms);

            return new DetailedAnalysisResult(text, detailedTerms, validTerms, namedEntities);
            
        } catch (Exception e) {
            log.warn("详细分析异常，返回基础结果: text={}, error={}", text, e.getMessage());
            List<String> fallbackTokens = fallbackTokenize(text);
            List<DetailedTerm> fallbackTerms = fallbackTokens.stream()
                    .map(token -> new DetailedTerm(token, "unknown", 0, token.length()))
                    .collect(Collectors.toList());
            return new DetailedAnalysisResult(text, fallbackTerms, fallbackTokens, Collections.emptyMap());
        }
    }


    /**
     * 添加自定义词汇（运行时动态添加）
     * 
     * <p><strong>这是添加自定义词汇的唯一编程入口</strong></p>
     * 
     * <p>使用场景：</p>
     * <ul>
     * <li>运行时动态添加新的专业术语</li>
     * <li>根据业务需求临时添加词汇</li>
     * <li>测试和调试时添加词汇</li>
     * </ul>
     * 
     * <p>词性建议：</p>
     * <ul>
     * <li>"nx" - 专有名词（产品名、品牌名）</li>
     * <li>"n" - 普通名词</li>
     * <li>"eng" - 英文术语</li>
     * </ul>
     *
     * @param term 词汇文本
     * @param pos 词性标签
     * @return 是否添加成功
     */
    public boolean addCustomTerm(String term, String pos) {
        if (StringUtils.isBlank(term) || StringUtils.isBlank(pos)) {
            log.warn("添加自定义词汇失败: 词汇或词性为空");
            return false;
        }

        try {
            String cleanTerm = term.trim();
            String cleanPos = pos.trim();
            
            // 检查是否已存在
            boolean isUpdate = customTerms.containsKey(cleanTerm);
            String oldPos = customTerms.get(cleanTerm);
            
            // 添加到本地词典
            customTerms.put(cleanTerm, cleanPos);
            
            // 立即同步到HanLP（高优先级）
            CustomDictionary.add(cleanTerm, cleanPos + CUSTOM_TERM_WEIGHT);
            
            if (isUpdate) {
                log.info("更新自定义词汇: {} [{}] -> [{}]", cleanTerm, oldPos, cleanPos);
            } else {
                log.info("新增自定义词汇: {} -> {} (词典大小: {})", cleanTerm, cleanPos, customTerms.size());
            }
            
            // 调试日志：显示HanLP同步信息
            log.debug("同步到HanLP: {} {} -> CustomDictionary", cleanTerm, cleanPos + CUSTOM_TERM_WEIGHT);
            
            return true;
            
        } catch (Exception e) {
            log.error("添加自定义词汇异常: term={}, pos={}, error={}", term, pos, e.getMessage());
            return false;
        }
    }

    /**
     * 批量添加自定义词汇
     * 
     * <p>用于一次性添加多个词汇，比单个添加效率更高</p>
     *
     * @param terms 词汇映射表（词汇 -> 词性）
     * @return 成功添加的数量
     */
    public int addCustomTermsBatch(Map<String, String> terms) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }

        int successCount = 0;
        for (Map.Entry<String, String> entry : terms.entrySet()) {
            if (addCustomTerm(entry.getKey(), entry.getValue())) {
                successCount++;
            }
        }
        
        log.info("批量添加自定义词汇: 请求={}, 成功={}", terms.size(), successCount);
        return successCount;
    }

    /**
     * 获取当前所有自定义词汇（只读副本，用于业务调用）
     * 
     * @return 自定义词典的副本，不可修改
     */
    public Map<String, String> getCustomTermsReadOnly() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(customTerms));
    }

    /**
     * 清空所有自定义词汇
     * 
     * <p><strong>注意：此操作不可逆</strong></p>
     */
    public void clearCustomTerms() {
        customTerms.clear();
        log.info("已清空所有自定义词汇");
    }

    /**
     * 创建分词器实例
     */
    private Segment createSegment() {
        try {
            // 使用HanLP的标准分词器，启用偏移量
            Segment segment = HanLP.newSegment().enableOffset(true);
            
            // 启用命名实体识别
            segment.enableNameRecognize(true);
            
            // 启用词性标注
            segment.enablePartOfSpeechTagging(true);
            
            return segment;
            
        } catch (Exception e) {
            log.error("创建HanLP分词器失败", e);
            throw new RuntimeException("无法创建分词器", e);
        }
    }

    /**
     * 判断是否为有效搜索词项（优化后的中文处理）
     */
    private boolean isValidSearchTerm(String word, String pos) {
        if (StringUtils.isBlank(word) || word.length() < MIN_TERM_LENGTH) {
            return false;
        }

        // 过滤停用词
        if (CHINESE_STOP_WORDS.contains(word)) {
            return false;
        }

        // 过滤纯标点符号和特殊字符
        if (word.matches(SYMBOL_FILTER_REGEX)) {
            return false;
        }

        // 过滤单个数字和字母（除非是自定义词汇）
        if (word.length() == 1 && word.matches(SINGLE_CHAR_PATTERN) && !customTerms.containsKey(word)) {
            return false;
        }

        // 检查词性（优先检查自定义词典）
        if (customTerms.containsKey(word)) {
            return true; // 自定义词汇优先保留
        }

        if (StringUtils.isNotBlank(pos)) {
            // 检查是否为有效的中文词性
            for (String validPos : VALID_POS_TAGS) {
                if (pos.startsWith(validPos)) {
                    return true;
                }
            }
        }

        // 兜底策略：长度合理的词汇也保留（可能是新词或专业术语）
        return word.length() >= 2 && word.length() <= 50;
    }

    /**
     * 提取命名实体（使用配置化映射）
     */
    private Map<String, List<String>> extractNamedEntities(List<Term> terms) {
        Map<String, List<String>> entities = new HashMap<>();

        for (Term term : terms) {
            if (term.nature == null || StringUtils.isBlank(term.word)) {
                continue;
            }

            String pos = term.nature.toString();
            
            // 使用配置化的词性到实体类型映射
            String entityType = POS_TO_ENTITY_TYPE.get(pos);
            
            // 特殊处理：eng也作为产品名
            if (entityType == null && "eng".equals(pos)) {
                entityType = "PRODUCT";
            }

            if (entityType != null) {
                entities.computeIfAbsent(entityType, k -> new ArrayList<>()).add(term.word);
            }
        }

        return entities;
    }

    /**
     * 降级分词策略（HanLP失败时使用）
     */
    private List<String> fallbackTokenize(String text) {
        try {
            // 使用兼容性更好的字符类进行分割
            String[] parts = text.split(FALLBACK_SPLIT_REGEX);
            return Arrays.stream(parts)
                    .filter(StringUtils::isNotBlank)
                    .filter(part -> part.length() >= FALLBACK_MIN_LENGTH || customTerms.containsKey(part))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            // 如果正则表达式仍然有问题，使用最简单的分割方式
            log.warn("正则分割失败，使用简单分割: {}", e.getMessage());
            String[] parts = text.split(FALLBACK_SIMPLE_REGEX);
            return Arrays.stream(parts)
                    .filter(StringUtils::isNotBlank)
                    .filter(part -> part.length() >= MIN_TERM_LENGTH)
                    .collect(Collectors.toList());
        }
    }


    /**
     * 详细分析结果数据类
     */
    public static class DetailedAnalysisResult {
        private final String originalText;
        private final List<DetailedTerm> allTerms;
        private final List<String> validTerms;
        private final Map<String, List<String>> namedEntities;

        public DetailedAnalysisResult(String originalText, List<DetailedTerm> allTerms, 
                                    List<String> validTerms, Map<String, List<String>> namedEntities) {
            this.originalText = originalText;
            this.allTerms = allTerms;
            this.validTerms = validTerms;
            this.namedEntities = namedEntities;
        }

        public String getOriginalText() { return originalText; }
        public List<DetailedTerm> getAllTerms() { return allTerms; }
        public List<String> getValidTerms() { return validTerms; }
        public Map<String, List<String>> getNamedEntities() { return namedEntities; }
    }

    /**
     * 详细词项数据类
     */
    public static class DetailedTerm {
        private final String word;
        private final String pos;
        private final int start;
        private final int end;

        public DetailedTerm(String word, String pos, int start, int end) {
            this.word = word;
            this.pos = pos;
            this.start = start;
            this.end = end;
        }

        public String getWord() { return word; }
        public String getPos() { return pos; }
        public int getStart() { return start; }
        public int getEnd() { return end; }
    }
}
