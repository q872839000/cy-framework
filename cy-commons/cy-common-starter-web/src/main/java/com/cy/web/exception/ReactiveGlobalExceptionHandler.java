package com.cy.web.exception;

import com.cy.core.base.R;
import com.cy.core.exception.CYException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.io.IOException;

/**
 * Reactive（WebFlux）环境全局异常处理器
 * <p>
 * 所有异常映射逻辑委托给 {@link ExceptionMapperSupport}，
 * 本类仅负责从 {@link ServerHttpRequest} 提取 URI 并适配 WebFlux 特有异常类型。
 * 与 Servlet 环境的 {@link GlobalExceptionHandler} 共享同一个映射内核，
 * 保证两种运行时对外输出格式完全一致。
 * </p>
 *
 * @author pcy
 * @date 2026/03/24
 * @see ExceptionMapperSupport 共享异常映射内核
 * @see GlobalExceptionHandler Servlet 环境对等实现
 */
@RestControllerAdvice
public class ReactiveGlobalExceptionHandler {

    private final ExceptionMapperSupport mapper;

    public ReactiveGlobalExceptionHandler(ExceptionMapperSupport mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(CYException.class)
    @ResponseStatus(HttpStatus.OK)
    public R<Void> handleCYException(CYException e, ServerHttpRequest request) {
        return mapper.mapCYException(e, request.getURI().getPath());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleWebExchangeBindException(WebExchangeBindException e, ServerHttpRequest request) {
        return mapper.mapValidationErrors(e.getBindingResult().getFieldErrors(), request.getURI().getPath());
    }

    @ExceptionHandler(ServerWebInputException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleServerWebInputException(ServerWebInputException e, ServerHttpRequest request) {
        return mapper.mapInputException(e.getReason(), e.getMessage(), request.getURI().getPath());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<Void> handleIllegalArgumentException(IllegalArgumentException e, ServerHttpRequest request) {
        return mapper.mapIllegalArgument(e, request.getURI().getPath());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public R<Void> handleResponseStatusException(ResponseStatusException e, ServerHttpRequest request) {
        return mapper.mapStatusException(e.getStatusCode().value(), e.getReason(), request.getURI().getPath());
    }

    @ExceptionHandler(NullPointerException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleNullPointerException(NullPointerException e, ServerHttpRequest request) {
        return mapper.mapNullPointer(e, request.getURI().getPath());
    }

    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleIOException(IOException e, ServerHttpRequest request) {
        return mapper.mapIOException(e, request.getURI().getPath());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleRuntimeException(RuntimeException e, ServerHttpRequest request) {
        return mapper.mapRuntime(e, request.getURI().getPath());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public R<Void> handleException(Exception e, ServerHttpRequest request) {
        return mapper.mapUnknown(e, request.getURI().getPath());
    }
}
