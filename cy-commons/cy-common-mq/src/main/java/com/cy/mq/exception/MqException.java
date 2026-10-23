package com.cy.mq.exception;

import java.io.Serial;

/**
 * MQ 组件基础异常。
 * <p>
 * 所有 MQ 相关异常的父类，用于统一异常处理。
 * </p>
 *
 * @author pcy
 * @date 2026/04/16
 */
public class MqException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MqException(String message) {
        super(message);
    }

    public MqException(String message, Throwable cause) {
        super(message, cause);
    }

    public MqException(Throwable cause) {
        super(cause);
    }
}
