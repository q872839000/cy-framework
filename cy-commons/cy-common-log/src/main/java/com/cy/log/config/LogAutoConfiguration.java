package com.cy.log.config;

import com.cy.log.aspect.AccessLogAspect;
import com.cy.log.aspect.ApiLogAspect;
import com.cy.log.aspect.SysLogAspect;
import com.cy.log.core.LogPublisher;
import com.cy.log.core.LogService;
import com.cy.log.core.LogServiceImpl;
import com.cy.log.filter.RequestCachingFilter;
import com.cy.log.metrics.LogMicrometerBridge;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.payload.LogRecordNormalizer;
import com.cy.log.persistence.AccessLogPersistenceListener;
import com.cy.log.persistence.LogCleanupService;
import com.cy.log.persistence.LogPersistenceListener;
import com.cy.log.persistence.LogTableRouter;
import com.cy.log.persistence.mapper.LogMapper;
import com.cy.log.persistence.service.LogQueryService;
import com.cy.log.persistence.service.LogQueryServiceImpl;
import com.cy.log.publish.FileLogFallbackStore;
import com.cy.log.publish.LogFallbackStore;
import com.cy.log.publish.LogTransport;
import com.cy.log.publish.MqLogTransport;
import com.cy.log.publish.NoopLogFallbackStore;
import com.cy.log.reactive.ReactorLogContextBridge;
import com.cy.log.request.RequestInfoResolver;
import com.cy.log.request.ServletRequestInfoResolver;
import com.cy.mq.config.MqAutoConfiguration;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqTemplate;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 日志组件自动配置。
 * <p>
 * 核心层包括：
 * </p>
 * <ul>
 *     <li>日志发布治理：{@link LogPublisher}</li>
 *     <li>系统日志切面：{@link SysLogAspect}</li>
 *     <li>访问链路切面：{@link AccessLogAspect}</li>
 *     <li>编程式日志服务：{@link LogService}</li>
 * </ul>
 * <p>
 * 核心层不依赖 Servlet，可运行于 Servlet、WebFlux 或其他非 Web 场景；
 * Servlet 专属能力在 {@link ServletConfiguration} 中按条件装配。
 * </p>
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
@AutoConfiguration(after = MqAutoConfiguration.class)
@EnableAspectJAutoProxy
@EnableConfigurationProperties(LogProperties.class)
@ConditionalOnProperty(prefix = "cy.log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LogAutoConfiguration {

    /**
     * 日志载荷统一处理器。
     * <p>
     * 所有日志类型（API、SysLog、Access、手动 LogService、响应式）的“对象 → 安全文本”
     * 都统一走该 bean，避免每个入口各写一套大对象 / 文件 / 流的处理逻辑。
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    public LogPayloadProcessor logPayloadProcessor(LogProperties properties) {
        return new LogPayloadProcessor(properties);
    }

    /**
     * 日志发布前的最终兜底归一化器。
     */
    @Bean
    @ConditionalOnMissingBean
    public LogRecordNormalizer logRecordNormalizer(LogProperties properties) {
        return new LogRecordNormalizer(properties);
    }

    /**
     * 日志传输层。
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(MqTemplate.class)
    @ConditionalOnMissingBean
    public LogTransport logTransport(MqTemplate mqTemplate) {
        return new MqLogTransport(mqTemplate);
    }

    /**
     * 日志主题命名解析器。
     */
    @Bean
    @ConditionalOnBean(MqTemplate.class)
    @ConditionalOnMissingBean
    public LogTopicNaming logTopicNaming(MqProperties mqProperties, LogProperties properties) {
        return new LogTopicNaming(mqProperties, properties);
    }

    /**
     * 日志主题目录。
     */
    @Bean
    @ConditionalOnBean(LogTopicNaming.class)
    @ConditionalOnMissingBean
    public LogTopicCatalog logTopicCatalog(LogProperties properties, LogTopicNaming logTopicNaming) {
        return new LogTopicCatalog(properties, logTopicNaming);
    }

    /**
     * 日志主题声明器。
     */
    @Bean
    @ConditionalOnBean({MqTemplate.class, LogTopicCatalog.class})
    @ConditionalOnMissingBean
    public LogTopicProvisioner logTopicProvisioner(MqTemplate mqTemplate, LogTopicCatalog logTopicCatalog) {
        return new LogTopicProvisioner(mqTemplate, logTopicCatalog);
    }

    /**
     * 本地兜底存储。
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public LogFallbackStore logFallbackStore(LogProperties properties) {
        if (properties.getPublish().getFallback().isEnabled()) {
            return new FileLogFallbackStore(properties.getPublish().getFallback());
        }
        return new NoopLogFallbackStore();
    }

    /**
     * 日志发布器。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(LogTransport.class)
    @ConditionalOnMissingBean
    public LogPublisher logPublisher(LogProperties properties,
                                     LogTopicNaming logTopicNaming,
                                     LogTransport logTransport,
                                     LogFallbackStore logFallbackStore,
                                     LogRecordNormalizer logRecordNormalizer) {
        log.info("初始化日志发布器: topic={}, accessTopic={}, businessQueue={}, accessQueue={}, maxPayloadBytes={}, maxBodyLength={}",
                logTopicNaming.businessTopic(),
                logTopicNaming.accessTopic(),
                properties.getPublish().getBusiness().getQueueCapacity(),
                properties.getPublish().getAccess().getQueueCapacity(),
                properties.getMaxPayloadBytes(),
                properties.getMaxBodyLength());
        return new LogPublisher(properties, logTopicNaming, logTransport, logFallbackStore, logRecordNormalizer);
    }

    /**
     * 编程式日志服务。
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(LogPublisher.class)
    @ConditionalOnMissingBean
    public LogService logService(LogPublisher logPublisher,
                                 LogProperties properties,
                                 Environment environment,
                                 ObjectProvider<RequestInfoResolver> resolverProvider,
                                 LogPayloadProcessor logPayloadProcessor) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        return new LogServiceImpl(logPublisher, properties, serviceName,
                resolverProvider.getIfAvailable(), logPayloadProcessor);
    }

    /**
     * 系统日志切面。
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(LogPublisher.class)
    @ConditionalOnMissingBean
    public SysLogAspect sysLogAspect(LogPublisher logPublisher,
                                     LogProperties properties,
                                     Environment environment,
                                     ObjectProvider<RequestInfoResolver> resolverProvider,
                                     BeanFactory beanFactory,
                                     LogPayloadProcessor logPayloadProcessor) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        log.info("初始化 SysLog 日志切面");
        return new SysLogAspect(logPublisher, properties, serviceName,
                resolverProvider.getIfAvailable(), beanFactory, logPayloadProcessor);
    }

    /**
     * ACCESS 日志切面。
     */
    @Bean
    @ConditionalOnBean(LogPublisher.class)
    @ConditionalOnProperty(prefix = "cy.log.access", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    public AccessLogAspect accessLogAspect(LogPublisher logPublisher,
                                           LogProperties properties,
                                           Environment environment,
                                           ObjectProvider<RequestInfoResolver> resolverProvider,
                                           BeanFactory beanFactory) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        log.info("初始化 ACCESS 日志切面: sampleRate={}, detailMode={}",
                properties.getAccess().getSampleRate(),
                properties.getAccess().getDetailMode());
        return new AccessLogAspect(logPublisher, properties, serviceName, resolverProvider.getIfAvailable(), beanFactory);
    }

    /**
     * Reactor 上下文桥接器。
     */
    @Bean
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "reactor.core.publisher.Hooks")
    @ConditionalOnMissingBean
    public ReactorLogContextBridge reactorLogContextBridge() {
        log.info("初始化 Reactor 日志上下文桥接器");
        return new ReactorLogContextBridge();
    }

    /**
     * API 日志切面。
     */
    @Bean
    @ConditionalOnClass(name = "jakarta.servlet.Servlet")
    @ConditionalOnBean(LogPublisher.class)
    @ConditionalOnMissingBean
    public ApiLogAspect apiLogAspect(LogPublisher logPublisher,
                                     LogProperties properties,
                                     Environment environment,
                                     RequestInfoResolver requestInfoResolver,
                                     LogPayloadProcessor logPayloadProcessor) {
        String serviceName = environment.getProperty("spring.application.name", "unknown");
        log.info("初始化 API 日志切面(Servlet)");
        return new ApiLogAspect(logPublisher, properties, serviceName, requestInfoResolver, logPayloadProcessor);
    }

    /**
     * Micrometer 指标桥接装配。
     * <p>
     * 必须独立成一个嵌套 {@code @Configuration} 静态类，把
     * {@link ConditionalOnClass} 落到 <b>类级别</b>。原因：当 {@code micrometer-core}
     * 不在 classpath 时，方法级 {@code @ConditionalOnClass(MeterRegistry.class)}
     * 仍然会被 Spring 在评估 {@link ConditionalOnBean} 时走到
     * {@code LogAutoConfiguration.getDeclaredMethods()}，JVM 强制解析方法签名里的
     * {@code MeterRegistry} 参数类型并抛 {@code NoClassDefFoundError}。
     * 落到嵌套类的类级别后，Spring Boot 通过 ASM 读取条件元数据，缺类时
     * 整个嵌套类被跳过，不进类加载、不解析参数类型，符合
     * {@code cy-common-log} pom 中 {@code micrometer-core} 标记为
     * {@code optional=true} 的真正语义。
     * </p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(io.micrometer.core.instrument.MeterRegistry.class)
    static class MicrometerBridgeConfiguration {

        /**
         * Micrometer 指标桥接。
         */
        @Bean
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        @ConditionalOnMissingBean
        public LogMicrometerBridge logMicrometerBridge(io.micrometer.core.instrument.MeterRegistry registry) {
            log.info("初始化日志 Micrometer 指标桥接");
            return new LogMicrometerBridge(registry);
        }
    }

    /**
     * Servlet 环境下的自动配置。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.log.publish", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnClass(name = "jakarta.servlet.Servlet")
    static class ServletConfiguration {

        /**
         * Servlet 请求信息解析器。
         */
        @Bean
        @ConditionalOnMissingBean(RequestInfoResolver.class)
        public RequestInfoResolver servletRequestInfoResolver() {
            return new ServletRequestInfoResolver();
        }

        /**
         * 请求体缓存过滤器注册。
         */
        @Bean
        @ConditionalOnMissingBean(name = "requestCachingFilterRegistration")
        public FilterRegistrationBean<RequestCachingFilter> requestCachingFilterRegistration(LogProperties properties) {
            FilterRegistrationBean<RequestCachingFilter> registration = new FilterRegistrationBean<>();
            registration.setFilter(new RequestCachingFilter(properties));
            registration.addUrlPatterns("/*");
            registration.setName("requestCachingFilter");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            return registration;
        }

    }

    /**
     * 持久化自动配置。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "cy.log.persistence", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "com.mybatisflex.core.BaseMapper")
    @MapperScan("com.cy.log.persistence.mapper")
    @EnableScheduling
    static class PersistenceConfiguration {

        @Bean("businessTableRouter")
        @ConditionalOnMissingBean(name = "businessTableRouter")
        public LogTableRouter businessTableRouter(LogMapper logMapper, LogProperties properties) {
            log.info("初始化业务日志分表路由: prefix={}, maxRows={}",
                    properties.getPersistence().getTablePrefix(),
                    properties.getPersistence().getMaxRowsPerTable());
            return new LogTableRouter(logMapper,
                    properties.getPersistence().getTablePrefix(),
                    properties.getPersistence().getMaxRowsPerTable());
        }

        @Bean("accessTableRouter")
        @ConditionalOnMissingBean(name = "accessTableRouter")
        public LogTableRouter accessTableRouter(LogMapper logMapper, LogProperties properties) {
            log.info("初始化 ACCESS 日志分表路由: prefix={}, maxRows={}",
                    properties.getAccess().getTablePrefix(),
                    properties.getAccess().getMaxRowsPerTable());
            return new LogTableRouter(logMapper,
                    properties.getAccess().getTablePrefix(),
                    properties.getAccess().getMaxRowsPerTable());
        }

        @Bean
        @ConditionalOnMissingBean
        public LogPersistenceListener logPersistenceListener(LogMapper logMapper,
                                                             @Qualifier("businessTableRouter") LogTableRouter businessTableRouter,
                                                             MqTemplate mqTemplate,
                                                             LogProperties properties,
                                                             LogTopicNaming logTopicNaming,
                                                             PlatformTransactionManager transactionManager) {
            log.info("初始化业务日志持久化监听器");
            return new LogPersistenceListener(logMapper, businessTableRouter, mqTemplate, properties, logTopicNaming, transactionManager);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "cy.log.access", name = "persist", havingValue = "true", matchIfMissing = true)
        public AccessLogPersistenceListener accessLogPersistenceListener(LogMapper logMapper,
                                                                         @Qualifier("accessTableRouter") LogTableRouter accessTableRouter,
                                                                         MqTemplate mqTemplate,
                                                                         LogProperties properties,
                                                                         LogTopicNaming logTopicNaming,
                                                                         PlatformTransactionManager transactionManager) {
            log.info("初始化 ACCESS 日志持久化监听器");
            return new AccessLogPersistenceListener(logMapper, accessTableRouter, mqTemplate, properties, logTopicNaming, transactionManager);
        }

        @Bean
        @ConditionalOnMissingBean
        public LogQueryService logQueryService(LogMapper logMapper,
                                               @Qualifier("businessTableRouter") LogTableRouter businessTableRouter,
                                               @Qualifier("accessTableRouter") LogTableRouter accessTableRouter,
                                               LogProperties properties) {
            return new LogQueryServiceImpl(logMapper, businessTableRouter, accessTableRouter, properties);
        }

        @Bean
        @ConditionalOnProperty(prefix = "cy.log.cleanup", name = "enabled", havingValue = "true")
        @ConditionalOnMissingBean
        public LogCleanupService logCleanupService(LogMapper logMapper,
                                                   LogProperties properties,
                                                   @Qualifier("businessTableRouter") LogTableRouter businessTableRouter,
                                                   @Qualifier("accessTableRouter") LogTableRouter accessTableRouter,
                                                   ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
            log.info("初始化日志清理服务: businessRetention={}d, accessRetention={}d, cron={}",
                    properties.getCleanup().getRetentionDays(),
                    properties.getAccess().getRetentionDays(),
                    properties.getCleanup().getCron());
            return new LogCleanupService(logMapper, properties,
                    businessTableRouter, accessTableRouter,
                    redisTemplateProvider.getIfAvailable());
        }
    }
}
