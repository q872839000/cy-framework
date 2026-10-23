package com.cy.log.aspect;

import com.cy.core.context.InvocationContext;
import com.cy.core.context.InvocationContextAccessor;
import com.cy.core.context.InvocationScope;
import com.cy.log.access.AccessFileLogger;
import com.cy.log.access.AccessInterceptor;
import com.cy.log.access.AccessLogContext;
import com.cy.log.access.AccessLogSerializer;
import com.cy.log.access.AccessSpan;
import com.cy.log.access.AccessTraceState;
import com.cy.log.annotation.AccessIntercept;
import com.cy.log.annotation.AccessLog;
import com.cy.log.config.LogProperties;
import com.cy.log.core.LogPublisher;
import com.cy.log.core.LogRecord;
import com.cy.log.core.LogType;
import com.cy.log.metrics.LogMetrics;
import com.cy.log.publish.LogDegradeLevel;
import com.cy.log.reactive.ReactiveLogSupport;
import com.cy.log.request.RequestInfo;
import com.cy.log.request.RequestInfoResolver;
import com.cy.log.util.LogUtils;
import com.cy.orm.utils.IdGeneratedUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 调用链路日志切面 - 拦截 @AccessLog 注解，记录方法级调用链路。
 * <p>
 * 支持嵌套调用追踪、采样控制、动态降级、响应式返回值处理。
 *
 * @author pcy
 * @date 2026/04/09
 */
@Slf4j
@Aspect
public class AccessLogAspect implements Ordered {

    private static final String REACTOR_MONO_CLASS_NAME = "reactor.core.publisher.Mono";

    /**
     * Reactor 是否在类路径上
     */
    private static final boolean REACTOR_PRESENT = ClassUtils.isPresent(
            REACTOR_MONO_CLASS_NAME, AccessLogAspect.class.getClassLoader());

    private final LogPublisher logPublisher;
    private final LogProperties properties;
    private final String serviceName;

    /**
     * 请求信息解析器（可选，非 Servlet 环境为 null）
     */
    private final RequestInfoResolver requestInfoResolver;

    private final BeanFactory beanFactory;

    /**
     * 拦截器实例缓存（按 Class 缓存，避免重复反射创建）
     */
    private final Map<Class<? extends AccessInterceptor>, AccessInterceptor> interceptorCache;

    public AccessLogAspect(LogPublisher logPublisher, LogProperties properties, String serviceName,
                           RequestInfoResolver requestInfoResolver,
                           BeanFactory beanFactory) {
        this.logPublisher = logPublisher;
        this.properties = properties;
        this.serviceName = serviceName;
        this.requestInfoResolver = requestInfoResolver;
        this.beanFactory = beanFactory;
        this.interceptorCache = new ConcurrentHashMap<>(properties.getInterceptorCacheSize());
    }

    @Pointcut("@within(com.cy.log.annotation.AccessLog) || @annotation(com.cy.log.annotation.AccessLog)")
    public void accessLogPointcut() {
    }

    @Around("accessLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 检查全局开关
        if (!properties.getAccess().isEnabled()) {
            return joinPoint.proceed();
        }

        // 获取注解（方法优先，类次之）
        AccessLog accessLog = resolveAnnotation(joinPoint);
        if (accessLog == null) {
            return joinPoint.proceed();
        }

        AccessTraceState state = AccessLogContext.currentState();
        boolean isRoot = state == null;
        if (isRoot) {
            InvocationContext invocationContext = InvocationContextAccessor.current();
            double sampleRate = properties.getAccess().getSampleRate();
            boolean sampled = sampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < sampleRate;
            double detailSampleRate = properties.getAccess().getDetailSampleRate();
            boolean detailSampled = sampled
                    && (detailSampleRate >= 1.0 || ThreadLocalRandom.current().nextDouble() < detailSampleRate);
            state = AccessLogContext.createState(sampled, detailSampled, invocationContext);
            AccessTraceState rootState = state;
            state.setPublishAction(() -> {
                try {
                    publishSpans(rootState);
                } catch (Exception e) {
                    log.error("发布调用链路日志失败", e);
                } finally {
                    AccessLogContext.clear();
                    AccessLogContext.remove();
                }
            });
            AccessLogContext.bind(state);
            if (!sampled) {
                LogMetrics.accessSampledOut();
            }
        }

        if (!state.isSampled()) {
            try {
                Object result = joinPoint.proceed();
                if (REACTOR_PRESENT && result != null) {
                    Object wrapped = tryPropagateReactiveState(result, state, InvocationContextAccessor.current());
                    if (wrapped != null) {
                        if (isRoot) {
                            AccessLogContext.remove();
                        }
                        return wrapped;
                    }
                }
                return result;
            } finally {
                if (isRoot) {
                    AccessLogContext.remove();
                }
            }
        }

        // 压栈
        AccessSpan span = AccessLogContext.push();
        if (span == null) {
            // 栈深度超限，直接执行不记录
            try {
                Object result = joinPoint.proceed();
                if (REACTOR_PRESENT && result != null) {
                    Object wrapped = tryPropagateReactiveState(result, state, InvocationContextAccessor.current());
                    if (wrapped != null) {
                        if (isRoot) {
                            AccessLogContext.remove();
                        }
                        return wrapped;
                    }
                }
                return result;
            } finally {
                if (isRoot) {
                    AccessLogContext.remove();
                }
            }
        }

        // 填充 span 基本信息
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> declaringClass = method.getDeclaringClass();
        span.setStartTime(System.currentTimeMillis());

        // 类名：Web 环境下，如果是 Controller，使用请求路径
        String moduleName = resolveModuleName(declaringClass, state, isRoot);
        span.setClassName(moduleName);
        span.setMethodName(method.getName());

        InvocationContext spanInvocationContext = resolveSpanInvocationContext(state, span);

        Object result;
        try (InvocationScope ignored = InvocationScope.open(spanInvocationContext)) {
            // 序列化入参
            if (shouldRecordInput(accessLog, state, span)) {
                int reqLimit = accessLog.reqCharLimit();
                if (reqLimit == -1) {
                    reqLimit = properties.getAccess().getDefaultReqCharLimit();
                }
                span.setInputArgs(AccessLogSerializer.serializeArgs(joinPoint.getArgs(), reqLimit));
            }

            // 解析 @AccessIntercept 注解，填充 module/operationDescribe，并调用 before 钩子
            AccessIntercept interceptAnno = method.getAnnotation(AccessIntercept.class);
            if (interceptAnno != null) {
                if (!interceptAnno.module().isEmpty()) {
                    span.setModule(interceptAnno.module());
                }
                if (!interceptAnno.operationDescribe().isEmpty()) {
                    span.setOperationDescribe(interceptAnno.operationDescribe());
                }
            }
            AccessInterceptor interceptor = resolveInterceptor(interceptAnno);
            if (interceptor != null) {
                try {
                    interceptor.before(interceptAnno, span);
                } catch (Exception e) {
                    log.debug("AccessInterceptor.before 执行异常: {}", e.getMessage());
                }
            }

            try {
                result = joinPoint.proceed();
            } catch (Throwable t) {
                span.setSuccess(false);
                span.setResultCode(AccessSpan.RESULT_ERROR);
                span.setErrorMessage(t.getMessage());
                completeSpan(state, span, false, null, accessLog, interceptor, interceptAnno);
                detachRootStateIfPending(isRoot, state);
                throw t;
            }

            // ====== 响应式路径：将 span 完成延迟到流结束时 ======
            if (REACTOR_PRESENT && result != null) {
                Object wrapped = tryWrapReactiveAccess(
                        result, state, span, spanInvocationContext, accessLog, interceptor, interceptAnno);
                if (wrapped != null) {
                    if (isRoot) {
                        AccessLogContext.remove();
                    }
                    return wrapped;
                }
            }

            // ====== 同步路径 ======
            completeSpan(state, span, true, result, accessLog, interceptor, interceptAnno);
            detachRootStateIfPending(isRoot, state);
            return result;
        }
    }

    /**
     * 尝试将响应式返回值包装为带 ACCESS 完成钩子的 Mono/Flux。
     * <p>
     * 当前实现会把 {@link AccessTraceState} 与当前活动 span 对应的
     * {@link InvocationContext} 一并写入 Reactor Context，并在流终止时统一完成 span。
     * 这样响应式回调即便切换线程，仍然能够在桥接器恢复的上下文中加入同一条 ACCESS trace，
     * 同时确保链路传播使用的是当前方法 span 的 spanId / parentSpanId。
     * </p>
     */
    private Object tryWrapReactiveAccess(Object result, AccessTraceState state,
                                         AccessSpan span,
                                         InvocationContext invocationContext,
                                         AccessLog accessLog,
                                         AccessInterceptor interceptor,
                                         AccessIntercept interceptAnno) {
        try {
            if (!ReactiveLogSupport.isReactive(result)) {
                return null;
            }

            return ReactiveLogSupport.wrapForAccessLog(
                    result,
                    state,
                    invocationContext,
                    (value, error, cancelled) -> {
                        if (error != null) {
                            span.setSuccess(false);
                            span.setResultCode(AccessSpan.RESULT_ERROR);
                            span.setErrorMessage(error.getMessage());
                        } else if (cancelled) {
                            span.setSuccess(false);
                            span.setResultCode(AccessSpan.RESULT_CANCELLED);
                            span.setErrorMessage(ReactiveLogSupport.cancelledMessage());
                        }
                        completeSpan(state, span, error == null && !cancelled, value,
                                accessLog, interceptor, interceptAnno);
                    }
            );
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    /**
     * 仅传播 ACCESS trace 状态与当前调用上下文到响应式链路，不附加任何完成逻辑。
     */
    private Object tryPropagateReactiveState(Object result, AccessTraceState state, InvocationContext invocationContext) {
        try {
            if (!ReactiveLogSupport.isReactive(result)) {
                return null;
            }
            return ReactiveLogSupport.propagateAccessState(result, state, invocationContext);
        } catch (NoClassDefFoundError e) {
            return null;
        }
    }

    /**
     * 完成 span 数据采集（线程安全，不访问 ThreadLocal）。
     * <p>
     * 同步路径和响应式路径共用。
     */
    private void finalizeSpan(AccessTraceState state,
                              AccessSpan span, boolean success, Object result,
                              AccessLog accessLog,
                              AccessInterceptor interceptor,
                              AccessIntercept interceptAnno) {
        span.setDuration(System.currentTimeMillis() - span.getStartTime());
        span.setCompleted(true);
        span.setSuccess(success);
        if (success) {
            span.setResultCode(AccessSpan.RESULT_SUCCESS);
        }

        if (success && shouldRecordOutput(accessLog, state, span) && result != null) {
            int retLimit = accessLog.retCharLimit();
            if (retLimit == -1) {
                retLimit = properties.getAccess().getDefaultRetCharLimit();
            }
            span.setReturnValue(AccessLogSerializer.serializeResult(result, retLimit));
        } else if (!success) {
            span.setReturnValue(span.getErrorMessage());
        }

        if (interceptor != null) {
            try {
                interceptor.complete(interceptAnno, span);
            } catch (Exception e) {
                log.error("AccessInterceptor.complete 执行失败: interceptor={}, span={}",
                    interceptor.getClass().getName(), span.getMethodName(), e);
            }
        }
    }

    /**
     * 完成 span 采集并在整条 trace 闭合时触发统一发布。
     */
    private void completeSpan(AccessTraceState state, AccessSpan span, boolean success, Object result,
                              AccessLog accessLog,
                              AccessInterceptor interceptor,
                              AccessIntercept interceptAnno) {
        finalizeSpan(state, span, success, result, accessLog, interceptor, interceptAnno);

        AccessLogContext.complete(span);
        if (state != null) {
            state.tryPublishIfReady();
        }
    }

    /**
     * 批量发布 span 日志。
     * <p>
     * 发布时统一从 {@link AccessTraceState} 中读取调用顺序列表与上下文快照，
     * 不再依赖回调线程上的 ThreadLocal 状态。
     */
    private void publishSpans(AccessTraceState state) {
        List<AccessSpan> spans = state != null ? List.copyOf(state.getOrderList()) : List.of();
        if (spans == null || spans.isEmpty()) {
            return;
        }
        List<AccessSpan> publishSpans = filterSpans(spans, state);
        if (publishSpans.isEmpty()) {
            return;
        }

        InvocationContext context = state != null ? state.getInvocationContext() : null;
        String traceId = LogUtils.resolveTraceId(context);
        String source = context != null ? context.getSource() : null;
        String clientIp = context != null ? context.getClientIp() : null;

        // 尝试从当前请求中补充信息（通过 SPI 解耦 Servlet）
        String requestUri = context != null ? context.getRequestUri() : null;
        String requestMethod = context != null ? context.getRequestMethod() : null;
        if ((!StringUtils.hasText(requestUri) || !StringUtils.hasText(requestMethod) || !StringUtils.hasText(clientIp))
                && requestInfoResolver != null) {
            try {
                RequestInfo reqInfo = requestInfoResolver.resolve();
                if (reqInfo != null) {
                    if (!StringUtils.hasText(requestUri)) {
                        requestUri = reqInfo.getRequestUri();
                    }
                    if (!StringUtils.hasText(requestMethod)) {
                        requestMethod = reqInfo.getRequestMethod();
                    }
                    if (!StringUtils.hasText(clientIp)) {
                        clientIp = reqInfo.getClientIp();
                    }
                }
            } catch (Exception ignored) {
            }
        }

        long now = System.currentTimeMillis();

        // 如果开启了本地文件输出，写入 access.log
        if (properties.getAccess().isEnableLogFile()) {
            AccessFileLogger.log(publishSpans, traceId, clientIp);
        }

        for (AccessSpan span : publishSpans) {
            if (!span.isCompleted()) {
                continue;
            }

            LogRecord record = new LogRecord();
            record.setId(IdGeneratedUtil.generateId());
            record.setTraceId(traceId);
            record.setSpanId(span.getSpanId());
            record.setParentSpanId(span.getParentSpanId());
            record.setSource(source);
            record.setLogType(LogType.ACCESS);
            record.setServiceName(serviceName);
            record.setServerIp(LogUtils.getServerIp());
            record.setClientIp(clientIp);
            record.setRequestUri(requestUri);
            record.setRequestMethod(requestMethod);

            // 调用链路专用字段
            record.setClassName(span.getClassName());
            record.setMethodName(span.getMethodName());
            record.setDepth(span.getDepth());
            record.setDuration(span.getDuration());
            record.setActualDuration(span.getActualDuration());
            record.setInputArgs(span.getInputArgs());
            record.setReturnValue(span.getReturnValue());

            // @AccessIntercept 注解的 module / operationDescribe
            if (span.getModule() != null) {
                record.setModule(span.getModule());
            }
            if (span.getOperationDescribe() != null) {
                record.setDescription(span.getOperationDescribe());
            }

            // 成功/失败
            record.setSuccess(span.isSuccess());
            if (span.getErrorMessage() != null) {
                record.setErrorMessage(LogUtils.truncate(span.getErrorMessage(), 1024));
            }

            // 用户信息
            LogUtils.applyInvocationUser(record, context);

            // 时间信息
            record.setCreateTime(span.getStartTime());
            record.setTablePartition(LogUtils.getPartition(now));

            logPublisher.publishAccess(record);
        }
    }

    /**
     * 判断当前 span 是否需要记录输入参数。
     */
    private boolean shouldRecordInput(AccessLog accessLog, AccessTraceState state, AccessSpan span) {
        return accessLog.recordInput()
                && shouldCaptureInputPayload()
                && shouldPublishSpan(span, resolveEffectiveDetailMode(), state);
    }

    /**
     * 判断当前 span 是否需要记录输出参数。
     */
    private boolean shouldRecordOutput(AccessLog accessLog, AccessTraceState state, AccessSpan span) {
        return accessLog.recordOutput()
                && shouldCaptureOutputPayload()
                && shouldPublishSpan(span, resolveEffectiveDetailMode(), state);
    }

    /**
     * 按当前降级状态过滤可发布的 span。
     */
    private List<AccessSpan> filterSpans(List<AccessSpan> spans, AccessTraceState state) {
        LogProperties.AccessDetailMode detailMode = resolveEffectiveDetailMode();
        return spans.stream()
                .filter(AccessSpan::isCompleted)
                .filter(span -> shouldPublishSpan(span, detailMode, state))
                .toList();
    }

    /**
     * 解析当前生效的 ACCESS 明细模式。
     */
    private LogProperties.AccessDetailMode resolveEffectiveDetailMode() {
        LogDegradeLevel degradeLevel = logPublisher.currentAccessDegradeLevel();
        if (degradeLevel == LogDegradeLevel.EMERGENCY) {
            return properties.getAccess().getEmergencyDetailMode();
        }
        if (degradeLevel == LogDegradeLevel.PRESSURE) {
            return properties.getAccess().getPressureDetailMode();
        }
        return properties.getAccess().getDetailMode();
    }

    /**
     * 判断当前状态下是否允许记录 payload 明细。
     */
    private boolean shouldCaptureInputPayload() {
        LogDegradeLevel degradeLevel = logPublisher.currentAccessDegradeLevel();
        if (degradeLevel == LogDegradeLevel.NORMAL) {
            return true;
        }
        return properties.getAccess().isRecordInputUnderPressure();
    }

    /**
     * 判断当前状态下是否允许记录输出 payload 明细。
     */
    private boolean shouldCaptureOutputPayload() {
        LogDegradeLevel degradeLevel = logPublisher.currentAccessDegradeLevel();
        if (degradeLevel == LogDegradeLevel.NORMAL) {
            return true;
        }
        return properties.getAccess().isRecordOutputUnderPressure();
    }

    /**
     * 判断指定 span 是否应当发布。
     */
    private boolean shouldPublishSpan(AccessSpan span,
                                      LogProperties.AccessDetailMode detailMode,
                                      AccessTraceState state) {
        if (span == null) {
            return false;
        }
        return switch (detailMode) {
            case NONE -> false;
            case ROOT_ONLY -> span.getDepth() == 1;
            case ERROR_ONLY -> span.getDepth() == 1 || !span.isSuccess();
            case SAMPLED_SPANS -> span.getDepth() == 1 || (state != null && state.isDetailSampled());
            case FULL -> true;
        };
    }

    /**
     * 根调用同步返回但 trace 尚未闭合时，主动解绑当前线程状态。
     * <p>
     * 这种情况通常出现在根方法同步返回了一个仍在异步执行的响应式链路，
     * 若不解绑，当前线程会保留一份已转交给 Reactor Context 的旧状态。
     * </p>
     */
    private void detachRootStateIfPending(boolean isRoot, AccessTraceState state) {
        if (isRoot && AccessLogContext.currentState() == state && !AccessLogContext.isComplete()) {
            AccessLogContext.remove();
        }
    }

    /**
     * 解析入口 span 的模块名。
     * <p>
     * Web Controller 根调用优先使用请求 URI；其他场景回退到声明类名。
     * </p>
     */
    private String resolveModuleName(Class<?> declaringClass, AccessTraceState state, boolean isRoot) {
        String moduleName = declaringClass.getSimpleName();
        if (!isRoot || !isWebController(declaringClass)) {
            return moduleName;
        }
        InvocationContext context = state != null ? state.getInvocationContext() : null;
        if (context != null && StringUtils.hasText(context.getRequestUri())) {
            return context.getRequestUri();
        }
        if (requestInfoResolver != null) {
            try {
                RequestInfo reqInfo = requestInfoResolver.resolve();
                if (reqInfo != null && StringUtils.hasText(reqInfo.getRequestUri())) {
                    return reqInfo.getRequestUri();
                }
            } catch (Exception ignored) {
            }
        }
        return moduleName;
    }

    /**
     * 基于当前 span 派生调用上下文。
     * <p>
     * 入口过滤器创建的 {@link InvocationContext} 保留服务级 trace / 用户 / 请求信息，
     * 此处仅覆盖 spanId / parentSpanId，使当前方法执行期间以及其派生的远程调用、
     * 异步回调都能感知“当前活动 span”的身份。
     * </p>
     */
    private InvocationContext resolveSpanInvocationContext(AccessTraceState state, AccessSpan span) {
        if (state == null || span == null) {
            return null;
        }
        InvocationContext context = state.getInvocationContext();
        if (context == null || !StringUtils.hasText(span.getSpanId())) {
            return null;
        }
        return context.toBuilder()
                .spanId(span.getSpanId())
                .parentSpanId(span.getParentSpanId())
                .build();
    }

    /**
     * 解析 @AccessLog 注解（方法优先，类次之）
     */
    private AccessLog resolveAnnotation(ProceedingJoinPoint joinPoint) {
        try {
            Signature sig = joinPoint.getSignature();
            if (!(sig instanceof MethodSignature methodSig)) {
                return null;
            }
            Method method = methodSig.getMethod();
            AccessLog annotation = method.getAnnotation(AccessLog.class);
            if (annotation != null) {
                return annotation;
            }
            return method.getDeclaringClass().getAnnotation(AccessLog.class);
        } catch (Exception e) {
            log.debug("解析 @AccessLog 注解失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 判断是否为 Web Controller 类
     */
    private boolean isWebController(Class<?> clazz) {
        return clazz.isAnnotationPresent(Controller.class)
                || clazz.isAnnotationPresent(RequestMapping.class)
                || clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class);
    }

    /**
     * 解析 @AccessIntercept 注解并获取拦截器实例（带缓存）
     */
    private AccessInterceptor resolveInterceptor(AccessIntercept interceptAnno) {
        if (interceptAnno == null) {
            return null;
        }
        Class<? extends AccessInterceptor> clazz = interceptAnno.interceptor();
        if (beanFactory != null) {
            try {
                return beanFactory.getBean(clazz);
            } catch (NoSuchBeanDefinitionException e) {
                log.debug("AccessInterceptor 未注册为 Spring Bean，将使用反射创建: {}", clazz.getName());
            } catch (BeansException e) {
                log.error("从 Spring 容器获取 AccessInterceptor 失败: class={}", clazz.getName(), e);
                return null;
            }
        }
        return interceptorCache.computeIfAbsent(clazz, cls -> {
            try {
                return cls.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                log.error("反射创建 AccessInterceptor 实例失败，请确保该类有无参构造函数: class={}", cls.getName(), e);
                return null;
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
