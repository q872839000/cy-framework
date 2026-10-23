package com.cy.web.exception;

import com.cy.core.base.R;
import com.cy.core.exception.CYException;
import com.cy.core.exception.MessageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.FieldError;

import java.io.IOException;
import java.util.List;

/**
 * 异常映射内核
 * <p>
 * 将异常统一映射为 {@link R} 响应结构。Servlet 和 Reactive 的异常处理器
 * 均委托此类完成映射，自身只负责适配各自运行时的请求类型和响应写入方式，
 * 保证两种环境的异常输出格式、错误码解析、日志级别完全一致。
 * </p>
 *
 * @author pcy
 * @date 2026/03/24
 */
public class ExceptionMapperSupport {

    private static final Logger logger = LoggerFactory.getLogger(ExceptionMapperSupport.class);

    private final MessageResolver messageResolver;

    public ExceptionMapperSupport(MessageResolver messageResolver) {
        this.messageResolver = messageResolver;
    }

    public R<Void> buildErrorResponse(int errorCode, Object... args) {
        String message = messageResolver.formatMessage(errorCode, args);
        return R.error(errorCode, message);
    }

    /**
     * 业务异常 → R（HTTP 200，业务错误码）
     */
    public R<Void> mapCYException(CYException e, String uri) {
        R<Void> response = buildErrorResponse(e.getErrorCode(), e.getArgs());
        logger.warn("业务异常 [{}] - URI: {} - Message: {}", e.getErrorCode(), uri, response.getMessage(), e);
        return response;
    }

    /**
     * 参数校验失败 → R（HTTP 400）
     */
    public R<Void> mapValidationErrors(List<FieldError> fieldErrors, String uri) {
        String detail = buildFieldErrorMessage(fieldErrors);
        logger.warn("参数校验异常 - URI: {} - Message: {}", uri, detail);
        return buildErrorResponse(90001);
    }

    /**
     * 参数绑定失败 → R（HTTP 400）
     */
    public R<Void> mapBindErrors(List<FieldError> fieldErrors, String uri) {
        String detail = buildFieldErrorMessage(fieldErrors);
        logger.warn("参数绑定异常 - URI: {} - Message: {}", uri, detail);
        return buildErrorResponse(90002);
    }

    /**
     * 非法参数 → R（HTTP 400）
     */
    public R<Void> mapIllegalArgument(IllegalArgumentException e, String uri) {
        logger.warn("参数异常 - URI: {} - Message: {}", uri, e.getMessage());
        return buildErrorResponse(90003);
    }

    /**
     * 空指针 → R（HTTP 500）
     */
    public R<Void> mapNullPointer(NullPointerException e, String uri) {
        logger.error("空指针异常 - URI: {}", uri, e);
        return buildErrorResponse(90006);
    }

    /**
     * 运行时异常 → R（HTTP 500）
     */
    public R<Void> mapRuntime(RuntimeException e, String uri) {
        logger.error("运行时异常 - URI: {}", uri, e);
        return buildErrorResponse(90007);
    }

    /**
     * 未知异常 → R（HTTP 500）
     */
    public R<Void> mapUnknown(Exception e, String uri) {
        logger.error("未知异常 - URI: {}", uri, e);
        return buildErrorResponse(90006);
    }

    public R<Void> mapThrowable(Throwable throwable, String uri) {
        if (throwable instanceof CYException e) {
            return mapCYException(e, uri);
        }
        if (throwable instanceof IllegalArgumentException e) {
            return mapIllegalArgument(e, uri);
        }
        if (throwable instanceof NullPointerException e) {
            return mapNullPointer(e, uri);
        }
        if (throwable instanceof RuntimeException e) {
            return mapRuntime(e, uri);
        }
        if (throwable instanceof Exception e) {
            return mapUnknown(e, uri);
        }
        logger.error("未知Throwable - URI: {}", uri, throwable);
        return buildErrorResponse(90006);
    }

    /**
     * IO 异常 → R（HTTP 500）
     */
    public R<Void> mapIOException(IOException e, String uri) {
        logger.error("IO异常 - URI: {}", uri, e);
        return buildErrorResponse(90008);
    }

    /**
     * HTTP 状态异常 → R（对应 HTTP 状态码）
     */
    public R<Void> mapStatusException(int statusCode, String reason, String uri) {
        logger.warn("HTTP 状态异常 - URI: {} - Status: {} - Reason: {}", uri, statusCode, reason);
        return buildErrorResponse(90005, statusCode);
    }

    /**
     * 请求输入异常 → R（HTTP 400）
     */
    public R<Void> mapInputException(String reason, String fallbackMessage, String uri) {
        logger.warn("请求输入异常 - URI: {} - Message: {}", uri, reason);
        return buildErrorResponse(90004);
    }

    private String buildFieldErrorMessage(List<FieldError> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < fieldErrors.size(); i++) {
            FieldError fieldError = fieldErrors.get(i);
            message.append(fieldError.getField()).append(" ").append(defaultDetail(fieldError.getDefaultMessage()));
            if (i < fieldErrors.size() - 1) {
                message.append(", ");
            }
        }
        return message.toString();
    }

    private String defaultDetail(String detail) {
        return detail != null ? detail : "";
    }
}
