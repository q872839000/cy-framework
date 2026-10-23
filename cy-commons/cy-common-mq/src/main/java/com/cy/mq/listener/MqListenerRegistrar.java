package com.cy.mq.listener;

import com.cy.mq.annotation.MqListener;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqBatchConsumer;
import com.cy.mq.core.MqConsumer;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import com.cy.mq.core.MqMetadata;
import com.cy.mq.core.MqSubscription;
import com.cy.mq.core.MqTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.EmbeddedValueResolverAware;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.util.StringValueResolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MQ 监听注解注册器。
 * <p>
 * 启动期扫描 Spring 容器内所有带 {@link MqListener} 注解的方法，解析签名并
 * 注册订阅。订阅回调由 {@link com.cy.mq.runtime.MqConsumerRuntime} 包装为
 * 标准 {@link com.cy.mq.core.MqRecordHandler}，runtime 负责 decode、trace
 * 还原、异常映射；本类只做：
 * </p>
 * <ol>
 *     <li>方法签名校验。</li>
 *     <li>反射调用业务方法。</li>
 *     <li>透传业务异常，让 runtime 据 {@link com.cy.mq.exception.MqConsumeException}
 *     等做出正确的 {@link com.cy.mq.core.ConsumeDecision}。</li>
 * </ol>
 * <p>
 * <b>支持的方法签名：</b>
 * </p>
 * <ul>
 *     <li>单条：{@code void m(T payload)}</li>
 *     <li>单条：{@code void m(T payload, MqMetadata metadata)}</li>
 *     <li>批量：{@code void m(List<T> payloads)}</li>
 *     <li>批量：{@code void m(List<T> payloads, List<MqMetadata> metadataList)}</li>
 * </ul>
 * <p>
 * payload 类型 {@code T} 可以是任意类型：POJO、{@code byte[]}、{@code String}、
 * {@code JSONObject}、{@code Map}、{@code Object}（通用 JSON 模型）。
 * </p>
 *
 * @author pcy
 */
@Slf4j
@RequiredArgsConstructor
public class MqListenerRegistrar implements ApplicationContextAware, SmartLifecycle, EmbeddedValueResolverAware {

    /**
     * MQ 模板。
     */
    private final MqTemplate mqTemplate;

    /**
     * MQ 配置。
     */
    private final MqProperties properties;

    /**
     * MQ 驱动注册中心。
     */
    private final MqDriverRegistry driverRegistry;

    /**
     * Spring 上下文。
     */
    private ApplicationContext applicationContext;

    /**
     * 可配置 BeanFactory（用于 SpEL 解析）。
     */
    private ConfigurableListableBeanFactory beanFactory;

    /**
     * 生命周期状态。
     */
    private volatile boolean running;

    /**
     * 已注册的订阅句柄。线程安全，便于并发注册与停机统一关闭。
     */
    private final List<MqSubscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * Spring 占位符解析器。
     */
    private StringValueResolver embeddedValueResolver;

    /**
     * SpEL 解析器，用于支持 {@code @MqListener(topic="#{...}")} 这类表达式。
     */
    private final BeanExpressionResolver beanExpressionResolver = new StandardBeanExpressionResolver();

    @Override
    public void setEmbeddedValueResolver(StringValueResolver resolver) {
        this.embeddedValueResolver = resolver;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        if (applicationContext instanceof ConfigurableApplicationContext configurableApplicationContext) {
            this.beanFactory = configurableApplicationContext.getBeanFactory();
        }
    }

    @Override
    public void start() {
        if (running || !properties.isEnabled()) {
            return;
        }
        running = true;
        try {
            registerAllListeners();
        } catch (Exception e) {
            running = false;
            // 注册失败时清理已建立的订阅，避免悬挂连接。
            for (MqSubscription subscription : subscriptions) {
                try {
                    subscription.close();
                } catch (Exception ex) {
                    log.debug("清理订阅失败", ex);
                }
            }
            subscriptions.clear();
            log.error("MqListenerRegistrar 启动失败", e);
            throw new IllegalStateException("MQ 监听器注册失败，应用启动中止", e);
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (MqSubscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception e) {
                log.debug("关闭 MQ 订阅失败", e);
            }
        }
        subscriptions.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 取最大 phase，确保在所有业务 bean 之后启动；优雅停机时最早停止。
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 扫描容器并注册所有监听方法。
     */
    private void registerAllListeners() {
        int registeredCount = 0;
        int failedCount = 0;
        List<String> failedListeners = new ArrayList<>();
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass == null) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                MqListener listener = method.getAnnotation(MqListener.class);
                if (listener == null) {
                    continue;
                }
                try {
                    if (registerOneListener(bean, method, listener)) {
                        registeredCount++;
                    }
                } catch (Exception e) {
                    failedCount++;
                    String failed = bean.getClass().getName() + "#" + method.getName();
                    failedListeners.add(failed);
                    log.error("注册 MQ 订阅失败: bean={}, method={}, defaultDriver={}",
                            bean.getClass().getName(), method.getName(), properties.getDriver(), e);
                }
            }
        }
        log.info("MQ 监听器扫描完成: registered={}, failed={}", registeredCount, failedCount);
        if (failedCount > 0) {
            throw new IllegalStateException(String.format(
                    "MQ 监听器注册失败: %d 个监听器注册失败，失败列表: %s",
                    failedCount, String.join(", ", failedListeners)));
        }
    }

    /**
     * 注册单个监听方法。
     */
    private boolean registerOneListener(Object bean, Method method, MqListener listener) {
        String topic = resolvePlaceholder(listener.topic());
        if (topic == null || topic.isBlank()) {
            throw new IllegalStateException(String.format(
                    "MQ 监听器 topic 不能为空: bean=%s, method=%s",
                    bean.getClass().getName(), method.getName()));
        }

        ListenerMethodMeta meta = parseListenerMethod(method, listener);
        MqDriverType resolvedDriver = driverRegistry.resolveDriverType(meta.driver());
        if (!driverRegistry.hasDriver(resolvedDriver)) {
            throw new IllegalStateException(String.format(
                    "MQ 监听器驱动未注册: bean=%s, method=%s, driver=%s, availableDrivers=%s",
                    bean.getClass().getName(), method.getName(),
                    resolvedDriver, driverRegistry.registeredDriverTypes()));
        }

        method.setAccessible(true);

        MqSubscription subscription;
        if (meta.batch()) {
            MqBatchConsumer<Object> consumer = (payloads, metadataList) ->
                    invokeBatchListener(bean, method, payloads, metadataList);
            @SuppressWarnings({"unchecked", "rawtypes"})
            MqSubscription registered = mqTemplate.subscribeBatch(
                    resolvedDriver, topic, meta.groupId(),
                    (Class) meta.payloadType(),
                    (MqBatchConsumer) consumer);
            subscription = registered;
        } else {
            MqConsumer<Object> consumer = (payload, metadata) ->
                    invokeListener(bean, method, payload, metadata);
            @SuppressWarnings({"unchecked", "rawtypes"})
            MqSubscription registered = mqTemplate.subscribe(
                    resolvedDriver, topic, meta.groupId(),
                    (Class) meta.payloadType(),
                    (MqConsumer) consumer);
            subscription = registered;
        }

        subscriptions.add(subscription);
        log.info("注册 MQ 订阅: bean={}, method={}, driver={}, topic={}, groupId={}, payloadType={}, batch={}",
                bean.getClass().getName(), method.getName(),
                resolvedDriver, topic, meta.groupId(), meta.payloadType().getName(), meta.batch());
        return true;
    }

    /**
     * 解析监听方法元数据。
     *
     * @param method   监听方法
     * @param listener 注解
     * @return 元数据
     */
    private ListenerMethodMeta parseListenerMethod(Method method, MqListener listener) {
        MqDriverType driver = resolveDriver(listener.driver());
        String groupId = resolvePlaceholder(listener.groupId());
        if (groupId != null && groupId.isBlank()) {
            groupId = null;
        }
        if (listener.batch()) {
            return parseBatchListenerMethod(method, listener, driver, groupId);
        }
        return parseSingleListenerMethod(method, listener, driver, groupId);
    }

    /**
     * 解析单条监听方法签名。
     * <p>
     * 合法签名：
     * </p>
     * <ul>
     *     <li>{@code void m(T payload)}</li>
     *     <li>{@code void m(T payload, MqMetadata metadata)}</li>
     * </ul>
     */
    private ListenerMethodMeta parseSingleListenerMethod(Method method, MqListener listener,
                                                         MqDriverType driver, String groupId) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 1 && parameterTypes.length != 2) {
            throw new IllegalStateException(String.format(
                    "MqListener 方法签名非法（参数个数必须为 1 或 2）: %s#%s",
                    method.getDeclaringClass().getName(), method.getName()));
        }
        if (parameterTypes.length == 2 && !MqMetadata.class.isAssignableFrom(parameterTypes[1])) {
            throw new IllegalStateException(String.format(
                    "MqListener 方法签名非法（第二个参数必须为 MqMetadata）: %s#%s",
                    method.getDeclaringClass().getName(), method.getName()));
        }

        Class<?> payloadType = listener.payloadType();
        if (payloadType == null || payloadType == Void.class) {
            payloadType = parameterTypes[0];
        }
        return new ListenerMethodMeta(driver, groupId, payloadType, false);
    }

    /**
     * 解析批量监听方法签名。
     * <p>
     * 合法签名：
     * </p>
     * <ul>
     *     <li>{@code void m(List<T> payloads)}</li>
     *     <li>{@code void m(List<T> payloads, List<MqMetadata> metadataList)}</li>
     * </ul>
     */
    private ListenerMethodMeta parseBatchListenerMethod(Method method, MqListener listener,
                                                        MqDriverType driver, String groupId) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        Type[] genericTypes = method.getGenericParameterTypes();
        if (parameterTypes.length != 1 && parameterTypes.length != 2) {
            throw new IllegalStateException(String.format(
                    "批量 MqListener 方法签名非法（参数个数必须为 1 或 2）: %s#%s",
                    method.getDeclaringClass().getName(), method.getName()));
        }
        if (!List.class.isAssignableFrom(parameterTypes[0])) {
            throw new IllegalStateException(String.format(
                    "批量 MqListener 第一个参数必须为 List: %s#%s",
                    method.getDeclaringClass().getName(), method.getName()));
        }
        if (parameterTypes.length == 2) {
            if (!List.class.isAssignableFrom(parameterTypes[1])) {
                throw new IllegalStateException(String.format(
                        "批量 MqListener 第二个参数必须为 List<MqMetadata>: %s#%s",
                        method.getDeclaringClass().getName(), method.getName()));
            }
            Class<?> metadataType = resolveListElementType(genericTypes[1]);
            if (metadataType == null || !MqMetadata.class.isAssignableFrom(metadataType)) {
                throw new IllegalStateException(String.format(
                        "批量 MqListener 第二个参数必须为 List<MqMetadata>: %s#%s",
                        method.getDeclaringClass().getName(), method.getName()));
            }
        }

        Class<?> payloadType = listener.payloadType();
        if (payloadType == null || payloadType == Void.class) {
            payloadType = resolveListElementType(genericTypes[0]);
        }
        if (payloadType == null || payloadType == Void.class) {
            throw new IllegalStateException(String.format(
                    "批量 MqListener 无法推断 payloadType（请显式声明 List 泛型或在注解中指定 payloadType）: %s#%s",
                    method.getDeclaringClass().getName(), method.getName()));
        }
        return new ListenerMethodMeta(driver, groupId, payloadType, true);
    }

    /**
     * 解析 {@code List<X>} 的泛型参数类型。
     */
    private Class<?> resolveListElementType(Type type) {
        if (!(type instanceof ParameterizedType parameterizedType)) {
            return null;
        }
        Type rawType = parameterizedType.getRawType();
        if (!(rawType instanceof Class<?> rawClass) || !List.class.isAssignableFrom(rawClass)) {
            return null;
        }
        Type actualType = parameterizedType.getActualTypeArguments()[0];
        if (actualType instanceof Class<?> actualClass) {
            return actualClass;
        }
        if (actualType instanceof ParameterizedType nestedType && nestedType.getRawType() instanceof Class<?> nestedClass) {
            return nestedClass;
        }
        return null;
    }

    private MqDriverType resolveDriver(String driver) {
        if (StringUtils.isBlank(driver)) {
            return null;
        }
        return MqDriverType.valueOf(driver.trim().toUpperCase());
    }

    /**
     * 调用单条监听方法。
     * <p>
     * 此处<b>不再</b>处理 trace 上下文：链路恢复已由
     * {@link com.cy.mq.runtime.MqConsumerRuntime} 与 {@link com.cy.mq.runtime.MqTraceSupport}
     * 在调用本方法前完成。本方法仅负责反射调度。
     * </p>
     */
    private void invokeListener(Object bean, Method method, Object payload, MqMetadata metadata) throws Exception {
        Object[] args = method.getParameterCount() == 1
                ? new Object[]{payload}
                : new Object[]{payload, metadata};
        invokeMethod(bean, method, args);
    }

    /**
     * 调用批量监听方法。
     */
    private void invokeBatchListener(Object bean, Method method, List<?> payloads, List<MqMetadata> metadataList)
            throws Exception {
        Object[] args = method.getParameterCount() == 1
                ? new Object[]{payloads}
                : new Object[]{payloads, metadataList};
        invokeMethod(bean, method, args);
    }

    /**
     * 反射调用业务方法，透传业务异常。
     * <p>
     * 优先通过代理对象调用，确保事务等切面生效；代理对象上找不到对应方法时回退到目标对象。
     * </p>
     */
    private void invokeMethod(Object bean, Method targetMethod, Object[] args) throws Exception {
        Method invokable = findInvokableMethod(bean, targetMethod);
        Method actualMethod = invokable != null ? invokable : targetMethod;
        Object actualBean = invokable != null ? bean : unwrapTarget(bean);
        try {
            actualMethod.invoke(actualBean, args);
        } catch (InvocationTargetException e) {
            // 解包业务异常并透传，让 runtime 按异常类型映射 ConsumeDecision。
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * 查找可在当前 Bean 上执行的方法（优先 public proxy 方法，确保 AOP 切面生效）。
     */
    private Method findInvokableMethod(Object bean, Method targetMethod) {
        try {
            Method method = bean.getClass().getMethod(targetMethod.getName(), targetMethod.getParameterTypes());
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 解包 Spring AOP 代理。
     */
    private Object unwrapTarget(Object bean) {
        if (!(bean instanceof Advised advised)) {
            return bean;
        }
        try {
            Object target = advised.getTargetSource().getTarget();
            return target != null ? target : bean;
        } catch (Exception e) {
            return bean;
        }
    }

    /**
     * 解析占位符 / SpEL：先 Spring 占位符，后 SpEL。
     */
    private String resolvePlaceholder(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String resolved = value;
        if (embeddedValueResolver != null) {
            resolved = embeddedValueResolver.resolveStringValue(value);
        }
        if (resolved != null && beanFactory != null && resolved.startsWith("#{") && resolved.endsWith("}")) {
            Object evaluated = beanExpressionResolver.evaluate(resolved, new BeanExpressionContext(beanFactory, null));
            return evaluated == null ? null : String.valueOf(evaluated);
        }
        return resolved;
    }

    /**
     * 监听方法元数据。
     *
     * @param driver      驱动类型
     * @param groupId     消费组
     * @param payloadType payload 类型
     * @param batch       是否批量
     */
    private record ListenerMethodMeta(MqDriverType driver, String groupId, Class<?> payloadType, boolean batch) {
    }
}
