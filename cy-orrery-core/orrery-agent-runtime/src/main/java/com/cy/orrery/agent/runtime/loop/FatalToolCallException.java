// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;

/**
 * 致命工具调用错误（不可自纠）。
 * <p>
 * 语义：LLM 改参数重试也无意义（如用户要的操作对象不存在于领域），
 * 引擎按部分失败语义处理——单调用场景立即中止本轮，多调用场景跳过该项继续，
 * 全部致命才整体中止。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class FatalToolCallException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean retryable;

    /**
     * 构造致命工具错误。
     *
     * @param errorCode 业务错误码（字符串）
     * @param message   面向用户的失败消息
     * @param retryable 是否可重试
     */
    public FatalToolCallException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * 业务错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }

    /**
     * 是否可重试。
     *
     * @return true 表示可重试
     */
    public boolean retryable() {
        return retryable;
    }
}
