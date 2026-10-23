package com.cy.mq.config;

import com.cy.mq.aspect.MqPublishAspect;
import com.cy.mq.codec.DefaultMqMessageCodec;
import com.cy.mq.codec.Fastjson2PayloadCodec;
import com.cy.mq.codec.MqMessageCodec;
import com.cy.mq.codec.MqPayloadCodec;
import com.cy.mq.core.DefaultMqTemplate;
import com.cy.mq.core.MqDriver;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqTemplate;
import com.cy.mq.driver.local.LocalMqDriver;
import com.cy.mq.driver.redis.RedisMqDriver;
import com.cy.mq.listener.MqListenerRegistrar;
import com.cy.mq.runtime.MqConsumerRuntime;
import com.cy.mq.runtime.MqProducerRuntime;
import com.cy.mq.validation.MqDriverReferenceValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 消息队列组件自动配置。
 * <p>
 * 注册顺序与依赖关系：
 * </p>
 * <ol>
 *     <li>{@link MqPayloadCodec} → {@link MqMessageCodec}：payload / metadata 编解码。</li>
 *     <li>{@link MqProducerRuntime}、{@link MqConsumerRuntime}：发布与消费运行时。</li>
 *     <li>各 driver（local / redis / kafka）：仅负责 broker IO。</li>
 *     <li>{@link MqDriverRegistry} → {@link MqTemplate}：业务面的统一入口。</li>
 *     <li>{@link MqPublishAspect}、{@link MqListenerRegistrar}：注解驱动的发布与订阅装配。</li>
 * </ol>
 *
 * @author pcy
 */
@Slf4j
@AutoConfiguration(after = {RedisAutoConfiguration.class},
        afterName = {"org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"})
@EnableAspectJAutoProxy
@ConditionalOnProperty(prefix = "cy.mq", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(MqProperties.class)
public class MqAutoConfiguration {

    // ==================== 编解码层 ====================

    /**
     * Payload 字节级编解码器。
     *
     * @return payload codec
     */
    @Bean
    @ConditionalOnMissingBean
    public MqPayloadCodec mqPayloadCodec() {
        return new Fastjson2PayloadCodec();
    }

    /**
     * Message 编解码器：负责 metadata + payload 与 broker 原始记录之间的转换。
     *
     * @param payloadCodec payload 编解码器
     * @return message codec
     */
    @Bean
    @ConditionalOnMissingBean
    public MqMessageCodec mqMessageCodec(MqPayloadCodec payloadCodec) {
        return new DefaultMqMessageCodec(payloadCodec);
    }

    // ==================== 运行时层 ====================

    /**
     * 发布运行时。
     *
     * @param registry     driver 注册中心
     * @param messageCodec message codec
     * @return producer runtime
     */
    @Bean
    @ConditionalOnMissingBean
    public MqProducerRuntime mqProducerRuntime(MqDriverRegistry registry, MqMessageCodec messageCodec) {
        return new MqProducerRuntime(registry, messageCodec);
    }

    /**
     * 消费运行时。
     *
     * @param messageCodec message codec
     * @return consumer runtime
     */
    @Bean
    @ConditionalOnMissingBean
    public MqConsumerRuntime mqConsumerRuntime(MqMessageCodec messageCodec) {
        return new MqConsumerRuntime(messageCodec);
    }

    // ==================== 执行器 ====================

    /**
     * MQ 执行器工厂。
     *
     * @return 执行器工厂
     */
    @Bean
    @ConditionalOnMissingBean
    public MqExecutorFactory mqExecutorFactory() {
        return new MqExecutorFactory();
    }

    /**
     * Local 驱动异步投递执行器。
     *
     * @param properties      配置
     * @param executorFactory 执行器工厂
     * @return Local 执行器
     */
    @Bean(name = "mqLocalExecutor")
    @ConditionalOnMissingBean(name = "mqLocalExecutor")
    public ThreadPoolTaskExecutor mqLocalExecutor(MqProperties properties, MqExecutorFactory executorFactory) {
        return executorFactory.createExecutor("cy-mq-local-", properties.getExecutors().getLocal());
    }

    // ==================== Local Driver ====================

    /**
     * 本地队列驱动。
     *
     * @param properties      配置
     * @param mqLocalExecutor 异步投递执行器
     * @return 本地队列驱动
     */
    @Bean
    @ConditionalOnMissingBean(LocalMqDriver.class)
    public LocalMqDriver localMqDriver(MqProperties properties,
                                       @Qualifier("mqLocalExecutor") ThreadPoolTaskExecutor mqLocalExecutor) {
        return new LocalMqDriver(properties.getLocal().isAsync(), mqLocalExecutor);
    }

    // ==================== Redis Driver ====================

    /**
     * Redis 消息监听容器（MQ 专用）。
     *
     * @param connectionFactory Redis 连接工厂
     * @return 消息监听容器
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(name = "mqRedisMessageListenerContainer")
    public RedisMessageListenerContainer mqRedisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    /**
     * Redis 驱动消费执行器。
     *
     * @param properties      配置
     * @param executorFactory 执行器工厂
     * @return Redis 执行器
     */
    @Bean(name = "mqRedisExecutor")
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(name = "mqRedisExecutor")
    public ThreadPoolTaskExecutor mqRedisExecutor(MqProperties properties, MqExecutorFactory executorFactory) {
        return executorFactory.createExecutor("cy-mq-redis-", properties.getExecutors().getRedis());
    }

    /**
     * Redis 队列驱动。
     * <p>
     * 同时支持 Pub/Sub 广播与 Stream 消费组两种模式：
     * </p>
     * <ul>
     *     <li>Pub/Sub：未指定 groupId，body 即 payload，无 framework metadata。</li>
     *     <li>Stream：指定 groupId，framework metadata 通过 stream entry 保留字段传输。</li>
     * </ul>
     *
     * @param redisTemplate                   Redis 操作模板
     * @param mqRedisMessageListenerContainer Pub/Sub 监听容器
     * @param properties                      MQ 配置
     * @param mqRedisExecutor                 消费执行器
     * @return Redis 驱动
     */
    @Bean
    @ConditionalOnClass(RedisConnectionFactory.class)
    @ConditionalOnBean({RedisConnectionFactory.class, StringRedisTemplate.class})
    @ConditionalOnMissingBean(RedisMqDriver.class)
    public RedisMqDriver redisMqDriver(StringRedisTemplate redisTemplate,
                                       RedisMessageListenerContainer mqRedisMessageListenerContainer,
                                       MqProperties properties,
                                       @Qualifier("mqRedisExecutor") ThreadPoolTaskExecutor mqRedisExecutor) {
        return new RedisMqDriver(redisTemplate, mqRedisMessageListenerContainer, properties, mqRedisExecutor);
    }

    // ==================== Driver 注册中心 / Template ====================

    /**
     * 驱动注册中心。
     *
     * @param properties 配置
     * @param drivers    所有自动发现的驱动
     * @return 注册中心
     */
    @Bean
    @ConditionalOnMissingBean
    public MqDriverRegistry mqDriverRegistry(MqProperties properties, List<MqDriver> drivers) {
        List<MqDriver> activeDrivers = drivers.stream()
                .filter(driver -> properties.isDriverEnabled(driver.type()))
                .toList();
        MqDriverRegistry registry = new MqDriverRegistry(properties);
        registry.registerDrivers(activeDrivers);
        log.info("初始化消息队列驱动: defaultDriver={}, registered={}, enabledDrivers={}, discovered={}",
                properties.getDriver(),
                activeDrivers.stream().map(driver -> driver.type().name()).toList(),
                properties.getResolvedEnabledDrivers(),
                drivers.stream().map(driver -> driver.type().name()).collect(Collectors.toList()));
        if (!properties.hasDefaultDriver()) {
            log.info("MQ 未配置默认驱动；当前应用仅支持显式指定 driver 的发布/订阅。若需使用默认驱动，请显式配置 cy.mq.driver。");
            return registry;
        }
        if (!registry.hasDriver(properties.getDriver())) {
            String message = "当前默认 MQ 驱动 %s 未完成注册，已注册驱动: %s。请检查相关依赖、Spring 自动配置以及 cy.mq.driver / cy.mq.enabled-drivers 配置。"
                    .formatted(properties.getDriver(), registry.registeredDriverTypes());
            if (properties.getValidation().isFailFastOnMissingDriver()) {
                throw new IllegalStateException(message);
            }
            log.error(message);
        }
        return registry;
    }

    /**
     * MQ 模板。
     *
     * @param registry        驱动注册中心
     * @param producerRuntime 发布运行时
     * @param consumerRuntime 消费运行时
     * @return MQ 模板
     */
    @Bean
    @ConditionalOnMissingBean
    public MqTemplate mqTemplate(MqDriverRegistry registry,
                                 MqProducerRuntime producerRuntime,
                                 MqConsumerRuntime consumerRuntime) {
        return new DefaultMqTemplate(registry, producerRuntime, consumerRuntime);
    }

    // ==================== 注解驱动 ====================

    /**
     * MQ 发布切面。
     *
     * @param mqTemplate MQ 模板
     * @param properties MQ 配置
     * @return 发布切面
     */
    @Bean
    @ConditionalOnMissingBean
    public MqPublishAspect mqPublishAspect(MqTemplate mqTemplate, MqProperties properties) {
        return new MqPublishAspect(mqTemplate, properties);
    }

    /**
     * MQ 订阅注解注册器。
     *
     * @param mqTemplate MQ 模板
     * @param properties MQ 配置
     * @param registry   驱动注册中心
     * @return 监听注册器
     */
    @Bean
    @ConditionalOnMissingBean
    public MqListenerRegistrar mqListenerRegistrar(MqTemplate mqTemplate,
                                                   MqProperties properties,
                                                   MqDriverRegistry registry) {
        return new MqListenerRegistrar(mqTemplate, properties, registry);
    }

    /**
     * 启动期校验：确保 {@code cy.mq.driver} 与 {@code @MqListener / @MqPublish} 引用的
     * driver 都已注册。
     *
     * @param properties MQ 配置
     * @param registry   驱动注册中心
     * @return 校验器
     */
    @Bean
    @ConditionalOnMissingBean
    public MqDriverReferenceValidator mqDriverReferenceValidator(MqProperties properties,
                                                                 MqDriverRegistry registry) {
        return new MqDriverReferenceValidator(properties, registry);
    }

    // ==================== Kafka 配置（独立内部类，避免类加载问题）====================

    /**
     * Kafka 配置类。
     * <p>
     * 将 Kafka 相关配置放在独立的内部类中，并通过条件注解控制是否启用：
     * 当 Kafka 依赖不存在或未显式配置 {@code spring.kafka.bootstrap-servers} 时，Spring 不会注册 Kafka MQ 驱动。
     * </p>
     */
    @Configuration
    @ConditionalOnClass(name = "org.springframework.kafka.core.KafkaTemplate")
    static class KafkaConfiguration {

        /**
         * Kafka 驱动消费执行器。
         *
         * @param properties      配置
         * @param executorFactory 执行器工厂
         * @return Kafka 执行器
         */
        @Bean(name = "mqKafkaExecutor")
        @ConditionalOnBean(type = {
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.kafka.core.ConsumerFactory"
        })
        @ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
        @ConditionalOnMissingBean(name = "mqKafkaExecutor")
        public ThreadPoolTaskExecutor mqKafkaExecutor(MqProperties properties, MqExecutorFactory executorFactory) {
            return executorFactory.createExecutor("cy-mq-kafka-", properties.getExecutors().getKafka());
        }

        /**
         * Kafka 队列驱动。
         * <p>
         * 复用 Spring Kafka 自动配置的连接，使用标准 {@code spring.kafka.*} 配置；
         * 仅在 {@code spring.kafka.bootstrap-servers} 已显式配置时才注册。
         * </p>
         *
         * @param applicationContext Spring 容器
         * @param mqKafkaExecutor    Kafka 执行器
         * @param properties         配置
         * @return Kafka 队列驱动
         */
        @Bean
        @ConditionalOnBean(type = {
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.kafka.core.ConsumerFactory"
        })
        @ConditionalOnProperty(prefix = "spring.kafka", name = "bootstrap-servers")
        @ConditionalOnMissingBean(name = "kafkaMqDriver")
        public MqDriver kafkaMqDriver(ApplicationContext applicationContext,
                                      @Qualifier("mqKafkaExecutor") ThreadPoolTaskExecutor mqKafkaExecutor,
                                      MqProperties properties) {
            Object kafkaTemplate = getRequiredBean(applicationContext,
                    "org.springframework.kafka.core.KafkaTemplate", "kafkaTemplate");
            Object consumerFactory = getRequiredBean(applicationContext,
                    "org.springframework.kafka.core.ConsumerFactory", "kafkaConsumerFactory");
            return createKafkaMqDriver(kafkaTemplate, consumerFactory, properties, mqKafkaExecutor);
        }

        /**
         * 动态创建 Kafka MQ 驱动。
         * <p>
         * 通过 reflective cast 创建驱动，避免在方法签名直接引用 Spring Kafka 类，
         * 让本配置类在类加载阶段对没有 Kafka 依赖的应用保持安全。
         * </p>
         */
        @SuppressWarnings("unchecked")
        private MqDriver createKafkaMqDriver(Object kafkaTemplate,
                                             Object consumerFactory,
                                             MqProperties properties,
                                             ThreadPoolTaskExecutor executor) {
            org.springframework.kafka.core.KafkaTemplate<?, ?> sourceTemplate =
                    (org.springframework.kafka.core.KafkaTemplate<?, ?>) kafkaTemplate;
            java.util.Map<String, Object> producerConfigs =
                    new java.util.HashMap<>(sourceTemplate.getProducerFactory().getConfigurationProperties());
            producerConfigs.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringSerializer.class);
            producerConfigs.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.ByteArraySerializer.class);
            org.springframework.kafka.core.KafkaTemplate<String, byte[]> kt =
                    new org.springframework.kafka.core.KafkaTemplate<>(
                            new org.springframework.kafka.core.DefaultKafkaProducerFactory<>(producerConfigs));

            org.springframework.kafka.core.ConsumerFactory<?, ?> cf =
                    (org.springframework.kafka.core.ConsumerFactory<?, ?>) consumerFactory;
            java.util.Map<String, Object> consumerConfigs = new java.util.HashMap<>(cf.getConfigurationProperties());
            consumerConfigs.put(org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.StringDeserializer.class);
            consumerConfigs.put(org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
            // 关闭自动 commit，让 driver 据 ConsumeDecision 显式 commit。
            consumerConfigs.put(org.apache.kafka.clients.consumer.ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            org.springframework.kafka.core.ConsumerFactory<String, byte[]> mqConsumerFactory =
                    new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(consumerConfigs);
            return new com.cy.mq.driver.kafka.KafkaMqDriver(kt, mqConsumerFactory, properties, executor);
        }

        private Object getRequiredBean(ApplicationContext applicationContext,
                                       String beanClassName,
                                       String preferredBeanName) {
            Class<?> beanType = org.springframework.util.ClassUtils.resolveClassName(
                    beanClassName, KafkaConfiguration.class.getClassLoader());
            if (applicationContext.containsBean(preferredBeanName)) {
                Object preferredBean = applicationContext.getBean(preferredBeanName);
                if (beanType.isInstance(preferredBean)) {
                    return preferredBean;
                }
            }
            return applicationContext.getBean(beanType);
        }
    }
}
