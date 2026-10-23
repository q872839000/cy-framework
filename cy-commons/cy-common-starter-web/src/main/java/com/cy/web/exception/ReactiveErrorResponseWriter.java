package com.cy.web.exception;

import com.alibaba.fastjson2.JSON;
import com.cy.core.base.R;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

public class ReactiveErrorResponseWriter {

    private final ExceptionMapperSupport mapper;

    public ReactiveErrorResponseWriter(ExceptionMapperSupport mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode httpStatus, int errorCode, Object... args) {
        return write(exchange, httpStatus, mapper.buildErrorResponse(errorCode, args));
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode httpStatus, R<?> body) {
        exchange.getResponse().setStatusCode(httpStatus);
        exchange.getResponse().getHeaders().set("Content-Type", "application/json;charset=UTF-8");
        byte[] bytes = JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse()
                .bufferFactory()
                .wrap(bytes)));
    }
}
