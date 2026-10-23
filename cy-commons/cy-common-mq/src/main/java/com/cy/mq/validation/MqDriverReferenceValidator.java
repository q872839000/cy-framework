package com.cy.mq.validation;

import com.cy.mq.annotation.MqListener;
import com.cy.mq.annotation.MqPublish;
import com.cy.mq.config.MqProperties;
import com.cy.mq.core.MqDriverRegistry;
import com.cy.mq.core.MqDriverType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.lang.reflect.Method;

/**
 * MQ 驱动引用校验器。
 * <p>
 * 在单例 Bean 完成初始化后，扫描所有 {@link MqListener} 与 {@link MqPublish}
 * 的驱动声明，确保显式驱动已被当前应用注册，且依赖默认驱动的注解声明
 * 在启动期具备明确的默认驱动配置。
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class MqDriverReferenceValidator implements ApplicationContextAware, SmartInitializingSingleton {

    /**
     * MQ 配置。
     */
    private final MqProperties properties;

    /**
     * 驱动注册中心。
     */
    private final MqDriverRegistry driverRegistry;

    /**
     * Spring 应用上下文。
     */
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            return;
        }
        validateDriverReferences();
    }

    /**
     * 扫描所有 Bean，校验驱动引用。
     */
    private void validateDriverReferences() {
        String[] beanNames = applicationContext.getBeanDefinitionNames();
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass == null) {
                continue;
            }
            for (Method method : targetClass.getDeclaredMethods()) {
                MqListener listener = method.getAnnotation(MqListener.class);
                if (listener != null) {
                    validateExplicitDriver(bean, method, "MqListener", listener.driver());
                }
                MqPublish publish = method.getAnnotation(MqPublish.class);
                if (publish != null) {
                    validateExplicitDriver(bean, method, "MqPublish", publish.driver());
                }
            }
        }
    }

    /**
     * 校验方法上的驱动配置。
     */
    private void validateExplicitDriver(Object bean, Method method, String source, String configuredDriver) {
        if (StringUtils.isBlank(configuredDriver)) {
            validateDefaultDriver(bean, method, source);
            return;
        }
        MqDriverType driverType;
        try {
            driverType = MqDriverType.valueOf(configuredDriver.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("无效的 MQ 驱动配置: source=" + source + ", bean=" + bean.getClass().getName()
                    + ", method=" + method.getName() + ", driver=" + configuredDriver, e);
        }
        if (driverRegistry.hasDriver(driverType)) {
            return;
        }
        String message = source + " 显式指定的 MQ 驱动未注册: bean=" + bean.getClass().getName()
                + ", method=" + method.getName() + ", driver=" + driverType
                + ", availableDrivers=" + driverRegistry.registeredDriverTypes()
                + "。请检查依赖、连接配置以及 cy.mq.enabled-drivers 配置。";
        if (properties.getValidation().isFailFastOnMissingDriver()) {
            throw new IllegalStateException(message);
        }
        log.error(message);
    }

    /**
     * 校验默认驱动依赖是否可用。
     */
    private void validateDefaultDriver(Object bean, Method method, String source) {
        if (properties.hasDefaultDriver()) {
            return;
        }
        String message = source + " 未显式指定 MQ 驱动，且当前应用未配置 cy.mq.driver: bean="
                + bean.getClass().getName()
                + ", method=" + method.getName()
                + "。请显式配置 cy.mq.driver，或在注解上通过 driver 指定具体驱动。";
        if (properties.getValidation().isFailFastOnMissingDriver()) {
            throw new IllegalStateException(message);
        }
        log.error(message);
    }
}
