package com.cy.textsearch.config;

import com.cy.textsearch.TextSearchFacade;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文本搜索自动配置类
 * <p>
 * 只对外暴露TextSearchFacade一个Bean，所有内部组件通过工厂模式在Facade内部创建。
 * </p>
 *
 * @author pcy
 */
@Configuration
@EnableConfigurationProperties(TextAnalysisProperties.class)
public class TextSearchAutoConfiguration {

    /**
     * 文本搜索门面（对外暴露的唯一接口）
     * <p>
     * 所有内部组件（ChineseTextProcessor、HanLPAnalyzer、LuceneIndexManager等）
     * 均在TextSearchFacade内部通过工厂模式创建，不再作为独立Bean暴露。
     * </p>
     *
     * @param properties 文本分析配置属性
     * @return TextSearchFacade实例
     */
    @Bean
    public TextSearchFacade textSearchFacade(TextAnalysisProperties properties) {
        return new TextSearchFacade(properties);
    }
}
