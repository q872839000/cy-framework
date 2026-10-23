package com.cy.log.aspect;

import com.cy.log.annotation.IgnoreLog;
import com.cy.log.config.LogProperties;
import com.cy.log.core.LogPublisher;
import com.cy.log.core.LogRecord;
import com.cy.log.core.LogRecordBuilder;
import com.cy.log.core.LogType;
import com.cy.log.filter.BoundedContentCachingRequestWrapper;
import com.cy.log.payload.FilePayloadDescriber;
import com.cy.log.payload.LogPayloadProcessor;
import com.cy.log.request.RequestInfoResolver;
import com.cy.log.util.LogUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * API 日志切面 - 零侵入式自动记录所有 Controller 的 HTTP 请求。
 * <p>
 * 支持审计日志升级、敏感数据脱敏、排除特定路径。
 *
 * @author pcy
 * @date 2026/04/06
 */
@Slf4j
@Aspect
public class ApiLogAspect {

    private final LogPublisher logPublisher;
    private final LogProperties properties;
    private final LogRecordBuilder recordBuilder;
    private final LogPayloadProcessor payloadProcessor;

    public ApiLogAspect(LogPublisher logPublisher, LogProperties properties, String serviceName,
                        RequestInfoResolver requestInfoResolver, LogPayloadProcessor payloadProcessor) {
        this.logPublisher = logPublisher;
        this.properties = properties;
        this.payloadProcessor = payloadProcessor != null ? payloadProcessor : new LogPayloadProcessor(properties);
        this.recordBuilder = new LogRecordBuilder(properties, serviceName, requestInfoResolver, this.payloadProcessor);
    }
    @Pointcut("(@within(org.springframework.web.bind.annotation.RestController) " +
            "|| @within(org.springframework.stereotype.Controller)) " +
            "&& execution(public * *(..))")
    public void controllerPointcut() {
    }

    /**
     * 环绕通知
     * <p>
     * 在Controller方法执行前后记录日志
     *
     * @param joinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常
     */
    @Around("controllerPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 获取请求上下文
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非HTTP请求环境，直接执行
            return joinPoint.proceed();
        }

        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();

        // 检查是否需要忽略
        if (shouldIgnore(joinPoint, request)) {
            return joinPoint.proceed();
        }

        // 记录开始时间
        long startTime = System.currentTimeMillis();

        // 构建日志记录（请求体处理委托 LogPayloadProcessor，自动识别 multipart / 二进制 / 大 JSON 等）
        LogRecord logRecord = buildLogRecord(request, joinPoint.getArgs());

        Object result = null;

        try {
            // 执行目标方法
            result = joinPoint.proceed();
            logRecord.setSuccess(true);
        } catch (Throwable e) {
            // 记录异常信息
            logRecord.setSuccess(false);
            logRecord.setErrorMessage(e.getMessage());
            logRecord.setStackTrace(LogUtils.getStackTrace(e));
            throw e;
        } finally {
            // 计算耗时
            long duration = System.currentTimeMillis() - startTime;
            logRecord.setDuration(duration);

            // 记录响应信息
            if (response != null) {
                logRecord.setResponseStatus(response.getStatus());
            }

            if (properties.isRecordResponseBody() && result != null) {
                // 响应体统一走 payloadProcessor，文件 / 流 / 大对象不会再触发 OOM
                logRecord.setResponseBody(payloadProcessor.processHttpResponseBody(result));
            }

            // 发布日志
            logPublisher.publish(logRecord);
        }

        return result;
    }

    /**
     * 判断是否需要忽略日志记录（检查 @IgnoreLog 注解和排除路径）。
     */
    private boolean shouldIgnore(ProceedingJoinPoint joinPoint, HttpServletRequest request) {
        // 检查@IgnoreLog注解
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();

        // 方法级别的@IgnoreLog
        if (method.isAnnotationPresent(IgnoreLog.class)) {
            return true;
        }

        // 类级别的@IgnoreLog
        Class<?> targetClass = joinPoint.getTarget().getClass();
        if (AnnotationUtils.findAnnotation(targetClass, IgnoreLog.class) != null) {
            return true;
        }

        // 检查排除路径
        String uri = request.getRequestURI();
        return LogUtils.isExcluded(uri, properties.getExcludePatterns());
    }

    private LogRecord buildLogRecord(HttpServletRequest request, Object[] controllerArgs) {
        String auditHeader = request.getHeader(properties.getAuditHeader());
        LogType logType = "true".equalsIgnoreCase(auditHeader) ? LogType.AUDIT : LogType.API;

        LogRecord record = recordBuilder.buildBaseRecord(logType);

        record.setRequestMethod(request.getMethod());
        record.setRequestUri(request.getRequestURI());

        String requestParams = LogUtils.extractParams(request);
        record.setRequestParams(payloadProcessor.process(requestParams));

        record.setRequestHeaders(LogUtils.extractHeaders(request));
        record.setClientIp(LogUtils.getClientIp(request));
        record.setUserAgent(request.getHeader("User-Agent"));

        if (properties.isRecordRequestBody()) {
            record.setRequestBody(buildRequestBody(request, controllerArgs));
        }

        return record;
    }

    /**
     * 通过 {@link LogPayloadProcessor} 安全地构建请求体的日志载荷。
     * <p>
     * 处理策略统一在 {@link LogPayloadProcessor#processHttpRequestBody}：
     * </p>
     * <ul>
     *     <li>multipart / 单文件 / 文件数组 → 文件清单（仅元信息，不读取内容）</li>
     *     <li>二进制 Content-Type → 类型 + 大小摘要</li>
     *     <li>普通文本 / JSON：从有界缓存中读取最多 {@code maxPayloadBytes} 字节</li>
     *     <li>超过 {@code maxPayloadBytes}：自动转 PREVIEW + truncated 标记</li>
     * </ul>
     */
    private String buildRequestBody(HttpServletRequest request, Object[] controllerArgs) {
        try {
            byte[] cachedBody = null;
            long observedBytes = -1;
            if (request instanceof BoundedContentCachingRequestWrapper wrapper) {
                cachedBody = wrapper.getContentAsByteArray();
                if (wrapper.isTruncated()) {
                    observedBytes = wrapper.getObservedBytes();
                }
            }
            long contentLength = observedBytes > 0 ? observedBytes : request.getContentLengthLong();
            String contentType = request.getContentType();
            Object multipartValue = resolveMultipartValue(controllerArgs);
            return payloadProcessor.processHttpRequestBody(cachedBody, contentType, contentLength, multipartValue);
        } catch (Exception e) {
            log.debug("构建请求体日志失败，已降级: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 controller 参数中识别 multipart 载荷（仅识别，不读取内容）。
     */
    private Object resolveMultipartValue(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (FilePayloadDescriber.isMultiFile(arg) || FilePayloadDescriber.isSingleFile(arg)) {
                return arg;
            }
        }
        return null;
    }
}
