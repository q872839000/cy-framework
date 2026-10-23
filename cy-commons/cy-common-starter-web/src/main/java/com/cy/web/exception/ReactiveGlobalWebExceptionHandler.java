package com.cy.web.exception;

import com.cy.core.base.R;
import com.cy.core.exception.CYException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.io.IOException;

public class ReactiveGlobalWebExceptionHandler implements WebExceptionHandler, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(ReactiveGlobalWebExceptionHandler.class);

    private final ExceptionMapperSupport mapper;
    private final ReactiveErrorResponseWriter writer;

    public ReactiveGlobalWebExceptionHandler(ExceptionMapperSupport mapper,
                                             ReactiveErrorResponseWriter writer) {
        this.mapper = mapper;
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            if (isClientAbortException(ex)) {
                logger.debug("Reactive 客户端连接已断开 - URI: {}", exchange.getRequest().getURI().getPath());
            } else {
                logger.error("Reactive 响应提交后发生异常 - URI: {}", exchange.getRequest().getURI().getPath(), ex);
            }
            return Mono.empty();
        }
        String uri = exchange.getRequest().getURI().getPath();
        return writer.write(exchange, resolveStatus(ex), mapException(ex, uri));
    }

    @Override
    public int getOrder() {
        return -2;
    }

    private R<Void> mapException(Throwable ex, String uri) {
        if (ex instanceof CYException e) {
            return mapper.mapCYException(e, uri);
        }
        if (ex instanceof WebExchangeBindException e) {
            return mapper.mapValidationErrors(e.getBindingResult().getFieldErrors(), uri);
        }
        if (ex instanceof ServerWebInputException e) {
            return mapper.mapInputException(e.getReason(), e.getMessage(), uri);
        }
        if (ex instanceof IllegalArgumentException e) {
            return mapper.mapIllegalArgument(e, uri);
        }
        if (ex instanceof ResponseStatusException e) {
            return mapper.mapStatusException(e.getStatusCode().value(), e.getReason(), uri);
        }
        if (ex instanceof IOException e) {
            return mapper.mapIOException(e, uri);
        }
        if (ex instanceof NullPointerException e) {
            return mapper.mapNullPointer(e, uri);
        }
        if (ex instanceof Exception e) {
            return mapper.mapUnknown(e, uri);
        }
        return mapper.mapThrowable(ex, uri);
    }

    private HttpStatusCode resolveStatus(Throwable ex) {
        if (ex instanceof CYException) {
            return HttpStatus.OK;
        }
        if (ex instanceof WebExchangeBindException
                || ex instanceof ServerWebInputException
                || ex instanceof IllegalArgumentException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ex instanceof ResponseStatusException e) {
            return e.getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean isClientAbortException(Throwable ex) {
        String className = ex.getClass().getName();
        if (className.contains("AbortedException") || className.contains("ClientAbortException")) {
            return true;
        }
        String message = ex.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("中止")
                || message.contains("Broken pipe")
                || message.contains("Connection reset")
                || message.contains("An established connection was aborted");
    }
}
