package com.cy.log.access;

import com.cy.log.annotation.AccessIntercept;

/**
 * 调用链路日志拦截器
 * <p>
 * 与 {@link AccessIntercept} 注解配合使用，可在方法执行前后注入自定义逻辑。
 * 典型使用场景：
 * <ul>
 *     <li>生成 PlantUML 时序图</li>
 *     <li>将调用链路推送到外部监控系统</li>
 *     <li>自定义入参/出参处理</li>
 * </ul>
 * <p>
 * 使用示例：
 * <pre>{@code
 * public class MyInterceptor implements AccessInterceptor {
 *     @Override
 *     public void before(AccessIntercept intercept, AccessSpan span) {
 *         // 方法执行前
 *     }
 *     @Override
 *     public void complete(AccessIntercept intercept, AccessSpan span) {
 *         // 方法执行后
 *     }
 * }
 * }</pre>
 *
 * @author pcy
 * @date 2026/04/09
 */
public interface AccessInterceptor {

    /**
     * 方法执行前调用
     *
     * @param accessIntercept 注解信息（包含 module、operationDescribe 等）
     * @param span            当前方法的调用链路 Span
     */
    default void before(AccessIntercept accessIntercept, AccessSpan span) {
    }

    /**
     * 方法执行后调用（无论成功或异常）
     *
     * @param accessIntercept 注解信息
     * @param span            当前方法的调用链路 Span（已填充 duration、returnValue 等）
     */
    default void complete(AccessIntercept accessIntercept, AccessSpan span) {
    }
}
