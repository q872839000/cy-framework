// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.loop;


import java.util.ArrayList;
import java.util.List;

/**
 * 单轮部分失败统计（主路径与重放路径共用，从 {@link LoopToolRoundEngine} 抽出）。
 *
 * @author pcy
 * @since 2026/09/02
 */
final class FailureTally {
    int totalCalls;
    int fatalCount;
    int successCount;
    String firstFatalCode;
    String firstFatalMsg;
    boolean firstFatalRetryable;
    final List<String> fatalMessages = new ArrayList<>();

    /**
     * 按总调用数构造统计。
     *
     * @param totalCalls 本轮总调用数
     * @return 统计实例
     */
    static FailureTally of(int totalCalls) {
        FailureTally tally = new FailureTally();
        tally.totalCalls = totalCalls;
        return tally;
    }

    /**
     * 记录一次致命失败（首例时锁定首例字段）。
     *
     * @param code      错误码
     * @param msg       失败消息
     * @param retryable 可重试标志
     */
    void recordFatal(String code, String msg, boolean retryable) {
        fatalCount++;
        if (firstFatalCode == null) {
            firstFatalCode = code;
            firstFatalMsg = msg;
            firstFatalRetryable = retryable;
        }
        fatalMessages.add(msg);
    }

    /**
     * 是否全部失败且零成功（本轮应按致命失败中止）。
     *
     * @return true = 全 fatal 无成功
     */
    boolean allFatalNoSuccess() {
        return fatalCount > 0 && successCount == 0;
    }

    /**
     * 致命失败消息合并（首个错误的详情展示用）。
     *
     * @return 分号连接的失败消息
     */
    String joinedDetail() {
        return String.join("；", fatalMessages);
    }
}
