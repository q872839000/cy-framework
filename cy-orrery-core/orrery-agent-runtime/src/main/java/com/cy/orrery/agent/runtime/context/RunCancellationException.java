// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;

/**
 * 运行取消异常：引擎在检查点检测到取消信号时抛出。
 * <p>
 * 由宿主的运行器统一捕获并转为 CANCELLED 终态语义；引擎自身不吞、不转译，
 * 保证取消路径在调用栈上显式可见。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class RunCancellationException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String runId;
    private final String phase;

    /**
     * 构造取消异常。
     *
     * @param runId 运行 ID
     * @param phase 取消检查点标识（如 round_3 / stage_fill）
     */
    public RunCancellationException(String runId, String phase) {
        super("run cancelled at " + phase + ": " + runId);
        this.runId = runId;
        this.phase = phase;
    }

    /**
     * 运行 ID。
     *
     * @return 运行 ID
     */
    public String runId() {
        return runId;
    }

    /**
     * 取消检查点标识。
     *
     * @return 检查点标识
     */
    public String phase() {
        return phase;
    }
}
