package com.cy.web.exception;

import com.alibaba.fastjson2.JSON;
import com.cy.core.base.R;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class ServletErrorResponseWriter {

    private final ExceptionMapperSupport mapper;

    public ServletErrorResponseWriter(ExceptionMapperSupport mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, int httpStatus, int errorCode, Object... args) throws IOException {
        write(response, httpStatus, mapper.buildErrorResponse(errorCode, args));
    }

    public void write(HttpServletResponse response, int httpStatus, R<?> body) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSON.toJSONString(body));
    }
}
