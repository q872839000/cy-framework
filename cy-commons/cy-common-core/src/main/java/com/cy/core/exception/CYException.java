package com.cy.core.exception;

import com.cy.core.utils.SpringUtils;
import java.io.Serial;

/**
 * 自定义业务异常类
 * 支持国际化消息和参数占位符
 *
 * @author pcy
 * @date 2026/1/7
 */
public class CYException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final Integer errorCode;

    /**
     * 消息参数
     */
    private final Object[] args;

    /**
     * 构造函数 - 仅错误码
     *
     * @param errorCode 错误码
     */
    public CYException(Integer errorCode) {
        this.errorCode = errorCode;
        this.args = new Object[0];
    }

    /**
     * 构造函数 - 错误码和参数
     *
     * @param errorCode 错误码
     * @param args      消息参数
     */
    public CYException(Integer errorCode, Object... args) {
        this.errorCode = errorCode;
        this.args = args != null ? args : new Object[0];
    }

    /**
     * 构造函数 - 错误码、异常原因
     *
     * @param errorCode 错误码
     * @param cause     异常原因
     */
    public CYException(Integer errorCode, Throwable cause) {
        super(cause);
        this.errorCode = errorCode;
        this.args = new Object[0];
    }

    /**
     * 构造函数 - 错误码、异常原因和参数
     *
     * @param errorCode 错误码
     * @param cause     异常原因
     * @param args      消息参数
     */
    public CYException(Integer errorCode, Throwable cause, Object... args) {
        super(cause);
        this.errorCode = errorCode;
        this.args = args != null ? args : new Object[0];
    }

    /**
     * 获取错误码
     *
     * @return 错误码
     */
    public Integer getErrorCode() {
        return errorCode;
    }

    /**
     * 获取消息参数
     *
     * @return 消息参数数组
     */
    public Object[] getArgs() {
        return args;
    }

    @Override
    public String getMessage() {
        try {
            MessageResolver messageResolver = SpringUtils.getBean(MessageResolver.class);
            if (messageResolver != null) {
                return messageResolver.formatMessage(errorCode, args);
            }
        } catch (Exception e) {
            // 如果获取MessageResolver失败，返回默认消息
        }
        // 降级处理：返回错误码和参数
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder("Error code: ").append(errorCode).append(" with args: [");
            for (int i = 0; i < args.length; i++) {
                sb.append(args[i]);
                if (i < args.length - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]");
            return sb.toString();
        }
        return "Error code: " + errorCode;
    }
}
