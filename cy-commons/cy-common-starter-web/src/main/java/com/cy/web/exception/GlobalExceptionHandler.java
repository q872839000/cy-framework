package com.cy.web.exception;

import com.cy.core.base.R;
import com.cy.core.exception.CYException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * Servlet 环境全局异常处理器
 * <p>
 * 所有异常映射逻辑委托给 {@link ExceptionMapperSupport}，
 * 本类仅负责从 {@link HttpServletRequest} 提取 URI 并适配 Servlet 特有行为（如 IO 异常）。
 * </p>
 *
 * @see ExceptionMapperSupport 共享异常映射内核
 * @see ReactiveGlobalExceptionHandler WebFlux 环境对等实现
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ExceptionMapperSupport mapper;

    public GlobalExceptionHandler(ExceptionMapperSupport mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(CYException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleCYException(CYException e, HttpServletRequest request) {
        return mapper.mapCYException(e, request.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleValidationException(MethodArgumentNotValidException e, HttpServletRequest request) {
        return mapper.mapValidationErrors(e.getBindingResult().getFieldErrors(), request.getRequestURI());
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleBindException(BindException e, HttpServletRequest request) {
        return mapper.mapBindErrors(e.getBindingResult().getFieldErrors(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgumentException(IllegalArgumentException e, HttpServletRequest request) {
        return mapper.mapIllegalArgument(e, request.getRequestURI());
    }

    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleNullPointerException(NullPointerException e, HttpServletRequest request) {
        return mapper.mapNullPointer(e, request.getRequestURI());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        return mapper.mapRuntime(e, request.getRequestURI());
    }

    @ExceptionHandler(IOException.class)
    public R<Void> handleIOException(IOException e, HttpServletRequest request, HttpServletResponse response) {
        if (response.isCommitted()) {
            if (isClientAbortException(e)) {
                logger.debug("客户端连接已断开 - URI: {}", request.getRequestURI());
            } else {
                logger.error("流式响应IO异常 - URI: {}", request.getRequestURI(), e);
            }
            return null;
        }
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return mapper.mapIOException(e, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, HttpServletRequest request) {
        return mapper.mapUnknown(e, request.getRequestURI());
    }

    private boolean isClientAbortException(IOException e) {
        String className = e.getClass().getName();
        if (className.contains("ClientAbortException")) {
            return true;
        }
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("中止")
                || message.contains("Broken pipe")
                || message.contains("Connection reset")
                || message.contains("An established connection was aborted");
    }
}
