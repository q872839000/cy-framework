package com.cy.ai.config;

import com.cy.ai.AiFacade;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * AI 模块自动配置类（Spring AI 风格）
 * <p>
 * 参考 Spring AI 的设计理念：
 * <ul>
 *     <li>每个 provider 独立配置（cy.ai.providers.&lt;id&gt;）</li>
 *     <li>扁平化配置结构（api-key/base-url + chat.options）</li>
 *     <li>provider 实例由 {@link AiProviderRegistry} 统一持有，支持运行期热更新</li>
 *     <li>通过 {@link AiFacade} 统一访问，支持按名称切换</li>
 * </ul>
 * </p>
 * <p>
 * <b>扩展新厂商：</b>在配置文件（或配置中心共享 dataId）中新增
 * cy.ai.providers.&lt;provider-name&gt; 配置块即可。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 * @see AiProperties
 * @see AiProviderRegistry
 * @see AiFacade
 */
@AutoConfiguration
@EnableConfigurationProperties(AiProperties.class)
public class AiAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AiAutoConfiguration.class);

    /**
     * AI 模块专用 ObjectMapper
     * <p>
     * 配置说明：
     * <ul>
     *     <li>命名策略：SNAKE_CASE（Java 的 camelCase 自动转换为 API 的 snake_case）</li>
     *     <li>序列化：忽略 null 字段，减少无效数据传输</li>
     *     <li>反序列化：忽略未知字段，兼容不同厂商的扩展响应</li>
     * </ul>
     * </p>
     *
     * @return 配置完成的 ObjectMapper 实例
     */
    @Bean(name = "aiObjectMapper")
    @ConditionalOnMissingBean(name = "aiObjectMapper")
    public ObjectMapper aiObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Provider 装配工厂（启动装配与运行期热刷新共用的构建路径）
     *
     * @param aiObjectMapper AI 专用 ObjectMapper
     * @return 工厂实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cy.ai", name = "default-provider")
    public AiProviderFactory aiProviderFactory(@Qualifier("aiObjectMapper") ObjectMapper aiObjectMapper) {
        return new AiProviderFactory(aiObjectMapper);
    }

    /**
     * Provider 动态注册表
     * <p>
     * 以当前 {@link AiProperties}（本地层与远程层经 Spring 属性源合并后的结果）
     * 构建初始快照；后续由 {@link AiProviderRefreshAutoConfiguration} 驱动热更新。
     * </p>
     *
     * @param factory    Provider 装配工厂
     * @param properties AI 模块配置属性
     * @return 注册表实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "cy.ai", name = "default-provider")
    public AiProviderRegistry aiProviderRegistry(AiProviderFactory factory, AiProperties properties) {
        return new AiProviderRegistry(factory, properties);
    }

    /**
     * 创建 AI 统一门面
     * <p>
     * 门面不持有任何 provider 状态，全部查询实时委托注册表当前快照。
     * </p>
     *
     * @param registry Provider 动态注册表
     * @return AiFacade 实例
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AiProviderRegistry.class)
    public AiFacade aiFacade(AiProviderRegistry registry) {
        log.info("AI 模块初始化完成，默认 provider: {}, 可用: {}",
                registry.snapshot().defaultProvider(), registry.snapshot().providers().keySet());
        return new AiFacade(registry);
    }
}
