package com.cy.core.utils;

import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationEvent;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Spring 上下文持有工具类。
 * <p>
 * 通过 {@link ApplicationContextAware} 在容器启动时捕获 {@link ApplicationContext}，
 * 提供静态方法供非 Spring 管理的对象获取 Bean、发布事件等。
 * </p>
 * <p>
 * <b>线程安全说明</b>：{@code context} 字段使用 {@code volatile} 保证
 * 容器线程写入后对其他线程立即可见。所有公开方法在容器就绪前调用将抛出
 * {@link IllegalStateException}，可通过 {@link #isReady()} 预检。
 * </p>
 *
 * @author pcy
 */
@Component
public final class SpringUtils implements ApplicationContextAware {

    /** volatile 保证跨线程可见性 */
    private static volatile ApplicationContext context;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext ctx) throws BeansException {
        context = ctx;
    }

    // ==================== Bean 获取 ====================

    /**
     * 获取 {@link ApplicationContext} 实例。
     *
     * @return ApplicationContext
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static ApplicationContext getContext() {
        requireContext();
        return context;
    }

    /**
     * 按类型获取 Bean。
     *
     * @param clazz Bean 类型
     * @param <T>   Bean 类型泛型
     * @return Bean 实例
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static <T> T getBean(Class<T> clazz) {
        requireContext();
        return context.getBean(clazz);
    }

    /**
     * 按名称获取 Bean。
     *
     * @param name Bean 名称
     * @return Bean 实例
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static Object getBean(String name) {
        requireContext();
        return context.getBean(name);
    }

    /**
     * 按名称和类型获取 Bean。
     *
     * @param name  Bean 名称
     * @param clazz Bean 类型
     * @param <T>   Bean 类型泛型
     * @return Bean 实例
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static <T> T getBean(String name, Class<T> clazz) {
        requireContext();
        return context.getBean(name, clazz);
    }

    // ==================== 事件 & 环境 ====================

    /**
     * 发布应用事件。
     *
     * @param event 事件对象
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static void publishEvent(ApplicationEvent event) {
        requireContext();
        context.publishEvent(event);
    }

    /**
     * 判断当前是否启用虚拟线程。
     *
     * @return {@code true} 表示虚拟线程已激活
     * @throws IllegalStateException 容器未就绪时抛出
     */
    public static boolean isVirtual() {
        return Threading.VIRTUAL.isActive(getBean(Environment.class));
    }

    // ==================== 状态检查 ====================

    /**
     * 检查 ApplicationContext 是否已注入就绪。
     * <p>
     * 用于在不确定容器状态时做前置判断，避免触发异常。
     * </p>
     *
     * @return {@code true} 表示可安全调用其他方法
     */
    public static boolean isReady() {
        return context != null;
    }

    // ==================== 内部方法 ====================

    private static void requireContext() {
        if (context == null) {
            throw new IllegalStateException(
                    "SpringUtils: ApplicationContext 尚未初始化，请确保在 Spring 容器启动完成后调用。");
        }
    }
}
