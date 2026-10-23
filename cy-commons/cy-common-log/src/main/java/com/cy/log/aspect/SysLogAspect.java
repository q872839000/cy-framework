package com.cy.log.aspect;

import com.cy.log.annotation.SysLog;
import com.cy.log.config.LogProperties;
import com.cy.log.core.LogPublisher;
import com.cy.log.core.LogRecord;
import com.cy.log.core.LogRecordBuilder;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.request.RequestInfoResolver;
import com.cy.log.util.LogUtils;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * 系统日志切面 - 处理 @SysLog 注解，记录业务操作日志。
 * <p>
 * 支持 SpEL 表达式、数据变更对比、响应式返回值处理。
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
@Aspect
public class SysLogAspect {

    /**
     * Reactor 是否在类路径上（用于 Mono/Flux 响应式完成时机处理）
     */
    private static final boolean REACTOR_PRESENT = ClassUtils.isPresent(
            "reactor.core.publisher.Mono", SysLogAspect.class.getClassLoader());

    private final LogPublisher logPublisher;
    private final LogProperties properties;
    private final LogRecordBuilder recordBuilder;
    private final LogPayloadProcessor payloadProcessor;
    private final SpelExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();
    private final BeanFactory beanFactory;

    public SysLogAspect(LogPublisher logPublisher, LogProperties properties, String serviceName,
                        RequestInfoResolver requestInfoResolver, BeanFactory beanFactory,
                        LogPayloadProcessor payloadProcessor) {
        this.logPublisher = logPublisher;
        this.properties = properties;
        this.payloadProcessor = payloadProcessor != null ? payloadProcessor : new LogPayloadProcessor(properties);
        this.recordBuilder = new LogRecordBuilder(properties, serviceName, requestInfoResolver, this.payloadProcessor);
        this.beanFactory = beanFactory;
    }

    /**
     * 切点定义
     * <p>
     * 匹配所有带有@SysLog注解的方法
     */
    @Pointcut("@annotation(com.cy.log.annotation.SysLog)")
    public void sysLogPointcut() {
    }

    /**
     * 环绕通知
     * <p>
     * 在方法执行前后记录系统日志
     *
     * @param joinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常
     */
    @Around("sysLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取方法签名和注解
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        SysLog sysLog = method.getAnnotation(SysLog.class);

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 构建日志记录
        LogRecord logRecord = buildLogRecord(sysLog, joinPoint);

        // ====== recordBefore：方法执行前采集原始数据 ======
        Object rawBeforeData = null;
        if (sysLog.recordBefore()) {
            rawBeforeData = resolveBeforeData(sysLog, joinPoint);
            if (rawBeforeData != null) {
                logRecord.setBeforeData(recordBuilder.toJsonWithSanitize(rawBeforeData));
            }
        }

        Object result;
        try {
            // 执行目标方法
            result = joinPoint.proceed();
        } catch (Throwable e) {
            // 方法调用本身抛异常（非响应式链路异常）
            logRecord.setSuccess(false);
            logRecord.setErrorMessage(e.getMessage());
            logRecord.setStackTrace(LogUtils.getStackTrace(e));
            logRecord.setDuration(System.currentTimeMillis() - startTime);
            logPublisher.publish(logRecord);
            throw e;
        }

        // ====== 响应式路径：将 afterData/duration/publish 延迟到流真正完成时 ======
        if (REACTOR_PRESENT && result != null) {
            Object wrapped = tryWrapReactive(result, logRecord, sysLog, rawBeforeData, startTime);
            if (wrapped != null) {
                return wrapped;
            }
        }

        // ====== 同步路径：立即处理 ======
        logRecord.setSuccess(true);

        if (sysLog.recordAfter() && result != null) {
            // 走统一处理器，文件 / 流 / 大对象不会再 OOM
            logRecord.setAfterData(payloadProcessor.process(result));
        }

        if (rawBeforeData != null && result != null && sysLog.recordAfter()) {
            String summary = payloadProcessor.processChangeSummary(rawBeforeData, result);
            if (summary != null && !summary.isEmpty()) {
                logRecord.setChangeSummary(summary);
            }
        }

        logRecord.setDuration(System.currentTimeMillis() - startTime);
        logPublisher.publish(logRecord);
        return result;
    }

    /**
     * 尝试将响应式返回值包装为带日志钩子的 Mono/Flux。
     * 返回 null 表示结果不是响应式类型，应走同步路径。
     */
    private Object tryWrapReactive(Object result, LogRecord logRecord, SysLog sysLog,
                                   Object rawBeforeData, long startTime) {
        try {
            if (!com.cy.log.reactive.ReactiveLogSupport.isReactive(result)) {
                return null;
            }
            return com.cy.log.reactive.ReactiveLogSupport.wrapForSysLog(
                    result, logRecord, sysLog.recordAfter(), rawBeforeData,
                    startTime, properties, logPublisher, payloadProcessor);
        } catch (NoClassDefFoundError e) {
            // 安全降级：reactor-core 未加载时走同步路径
            return null;
        }
    }

    /**
     * 解析修改前数据
     * <p>
     * 优先使用 beforeDataEl SpEL 表达式求值，若未配置则回退到序列化方法入参。
     *
     * @param sysLog    注解
     * @param joinPoint 切点
     * @return 修改前数据对象
     */
    private Object resolveBeforeData(SysLog sysLog, ProceedingJoinPoint joinPoint) {
        try {
            String beforeDataEl = sysLog.beforeDataEl();
            if (StringUtils.hasText(beforeDataEl)) {
                // 使用 SpEL 表达式求值（支持 @beanName 引用）
                return evaluateSpelExpression(beforeDataEl, joinPoint);
            }
            // 回退：序列化方法入参作为修改前快照
            Object[] args = joinPoint.getArgs();
            if (args.length == 1) {
                return args[0];
            }
            return args;
        } catch (Exception e) {
            log.warn("解析修改前数据失败: {}", e.getMessage());
            return null;
        }
    }

    private LogRecord buildLogRecord(SysLog sysLog, ProceedingJoinPoint joinPoint) {
        LogRecord record = recordBuilder.buildBaseRecord(sysLog.logType());

        record.setModule(sysLog.module());
        record.setOperation(sysLog.operation());
        record.setTargetType(sysLog.targetType());

        String description = sysLog.description();
        if (StringUtils.hasText(description)) {
            record.setDescription(parseSpelExpression(description, joinPoint));
        }

        String targetIdEl = sysLog.targetIdEl();
        if (StringUtils.hasText(targetIdEl)) {
            record.setTargetId(parseSpelExpression(targetIdEl, joinPoint));
        }

        return record;
    }

    /**
     * 解析SpEL表达式（返回字符串）
     *
     * @param expressionString 表达式字符串
     * @param joinPoint        切点
     * @return 解析结果字符串
     */
    private String parseSpelExpression(String expressionString, ProceedingJoinPoint joinPoint) {
        try {
            // 如果不是SpEL表达式，直接返回
            if (!expressionString.contains("#") && !expressionString.contains("@")) {
                return expressionString;
            }

            Object value = evaluateSpelExpression(expressionString, joinPoint);
            return value != null ? value.toString() : null;

        } catch (Exception e) {
            log.error("SpEL 表达式解析失败，可能存在语法错误或引用了不存在的变量: expression={}", expressionString, e);
            return expressionString;
        }
    }

    /**
     * 求值SpEL表达式（返回原始对象）
     * <p>
     * 支持 #参数名 引用方法参数，支持 @beanName 引用 Spring Bean。
     *
     * @param expressionString 表达式字符串
     * @param joinPoint        切点
     * @return 求值结果
     */
    private Object evaluateSpelExpression(String expressionString, ProceedingJoinPoint joinPoint) {
        // 获取方法参数
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);

        // 构建SpEL上下文
        StandardEvaluationContext context = new StandardEvaluationContext();
        if (parameterNames != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        // 注册 BeanFactory，支持 @beanName.method() 语法
        if (beanFactory != null) {
            context.setBeanResolver(new BeanFactoryResolver(beanFactory));
        }

        // 解析表达式
        Expression expression = spelParser.parseExpression(expressionString);
        return expression.getValue(context);
    }
}
