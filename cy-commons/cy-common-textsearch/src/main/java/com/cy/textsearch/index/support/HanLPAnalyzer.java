package com.cy.textsearch.index.support;

import com.cy.textsearch.core.ChineseTextProcessor;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.List;

/**
 * 优化的HanLP Lucene分析器
 * <p>
 * 重构后的分析器，使用统一的ChineseTextProcessor：
 * 1. 消除与HanLPTextAnalysisService的重复逻辑
 * 2. 确保索引和查询时的分词一致性
 * 3. 支持自定义专有名词处理
 * 4. 优化中文文本处理策略
 * </p>
 *
 * <strong>设计改进：</strong>
 * - 依赖注入：使用Spring管理的ChineseTextProcessor
 * - 一致性：与文本分析服务使用相同的分词策略
 * - 专业化：专门针对搜索场景优化
 * - 可维护：单一数据源，统一维护分词逻辑
 *
 * @author pcy
 * @date 2025年12月19日
 */
public class HanLPAnalyzer extends Analyzer {

    /**
     * 统一的中文文本处理器
     */
    private final ChineseTextProcessor textProcessor;

    /**
     * 构造函数
     *
     * @param textProcessor 中文文本处理器
     */
    public HanLPAnalyzer(ChineseTextProcessor textProcessor) {
        this.textProcessor = textProcessor;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        // 创建使用统一处理器的Tokenizer
        Tokenizer tokenizer = new UnifiedChineseTokenizer(textProcessor);
        
        // 应用小写过滤器（对英文词汇有效）
        return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
    }

    /**
     * 统一的中文Tokenizer实现
     * <p>
     * 使用ChineseTextProcessor提供的分词能力，确保：
     * - 与文本分析服务的一致性
     * - 支持自定义词典和专有名词
     * - 优化的中文处理策略
     * </p>
     */
    public static class UnifiedChineseTokenizer extends Tokenizer {

        private final ChineseTextProcessor processor;
        private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
        private final OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);
        private final PositionIncrementAttribute posIncrAttr = addAttribute(PositionIncrementAttribute.class);

        private Iterator<String> tokenIterator;
        private String currentText;
        private int currentPosition;
        private int finalOffset;

        /**
         * 构造函数
         *
         * @param processor 中文文本处理器
         */
        public UnifiedChineseTokenizer(ChineseTextProcessor processor) {
            this.processor = processor;
        }

        @Override
        public boolean incrementToken() throws IOException {
            // 延迟初始化，确保输入流已准备好
            if (tokenIterator == null) {
                initialize();
            }

            // 遍历分词结果
            while (tokenIterator.hasNext()) {
                String token = tokenIterator.next();
                
                // 跳过空白token（理论上ChineseTextProcessor已过滤）
                if (token == null || token.isEmpty()) {
                    continue;
                }

                // 设置token属性
                clearAttributes();
                termAttr.append(token);
                termAttr.setLength(token.length());

                // 计算token在原文中的位置（简化版，实际可能需要更精确的位置计算）
                int start = findTokenPosition(token, currentPosition);
                int end = start + token.length();
                
                offsetAttr.setOffset(correctOffset(start), correctOffset(end));
                posIncrAttr.setPositionIncrement(1);
                
                // 更新位置指针
                currentPosition = end;
                return true;
            }
            
            return false; // 没有更多token
        }

        @Override
        public void reset() throws IOException {
            super.reset();
            tokenIterator = null;
            currentText = null;
            currentPosition = 0;
            finalOffset = 0;
        }

        @Override
        public void end() throws IOException {
            super.end();
            int corrected = correctOffset(finalOffset);
            offsetAttr.setOffset(corrected, corrected);
        }

        /**
         * 初始化分词器
         * <p>
         * 读取输入流并使用ChineseTextProcessor进行分词
         * </p>
         */
        private void initialize() throws IOException {
            // 读取完整文本
            currentText = readAllText(input);
            finalOffset = currentText.length();
            
            // 使用统一的文本处理器进行分词
            List<String> tokens = processor.tokenize(currentText);
            tokenIterator = tokens.iterator();
            
            // 重置位置指针
            currentPosition = 0;
        }

        /**
         * 查找token在原文中的位置
         * <p>
         * 简化实现：从当前位置开始搜索token
         * 实际应用中可能需要更精确的位置匹配算法
         * </p>
         */
        private int findTokenPosition(String token, int fromIndex) {
            if (currentText == null || token == null) {
                return fromIndex;
            }
            
            int pos = currentText.indexOf(token, fromIndex);
            return pos >= 0 ? pos : fromIndex;
        }

        /**
         * 读取输入流的所有文本
         */
        private static String readAllText(Reader reader) throws IOException {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096]; // 使用更大的缓冲区提高效率
            int length;
            
            while ((length = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, length);
            }
            
            return sb.toString();
        }
    }
}
