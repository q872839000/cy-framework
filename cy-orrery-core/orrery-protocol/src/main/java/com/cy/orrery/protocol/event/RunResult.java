// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@link EventPayload.Final} / {@link EventPayload.RunFailed} 的通用运行结果（Agent 事件协议 v1 标准内核）
 * <p>
 * <b>领域无关</b>:只承载任何 Agent 都通用的运行遥测——停止原因、循环轮次、工具调用次数。具体业务结果
 * 一律不进内核,改放进 {@link #ext} 扩展位,由各 Agent 用自己的强类型扩展对象填充。这样“标准”保持稳定、
 * 可被所有 Agent 复用,而领域差异在 {@code ext} 里自由演进,互不污染。
 * </p>
 * <p>
 * 消费端约定:{@code ext} 是 JSON 对象,形状由发射方 Agent 自描述;前端 / Java 消费者按所对接的 Agent
 * 反序列化(Java 侧可 {@code objectMapper.convertValue(result.ext(), XxxResult.class)}）。{@code ext} 可为
 * {@code null}（如失败终态没有完整业务结果）。
 * </p>
 *
 * <p>
 * 与各负载一致,类级 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证同 major 内新增可选字段时
 * 老消费者忽略未知字段而非失败(本 record 嵌在 {@code data.result} 内,不被 {@link EventPayload} 上的注解覆盖,需自带)。
 * </p>
 *
 * @param stop        停止原因(机器标识,如 {@code FINAL_ANSWER} / {@code MAX_STEPS} / {@code REJECTED}）
 * @param rounds      LLM 循环轮次
 * @param toolCalls   工具调用总次数
 * @param resumeToken 续跑句柄:非 null 表示本次运行暂停可续,消费端应持有并在下一轮原样回传以续接;
 *                    null 表示本次运行终结、不可续。句柄是不透明字符串,内部结构由发射方 Agent 维护。
 * @param ext         领域扩展结果(本质开放,可为 null);由发射方 Agent 定义其形状
 * @author pcy
 * @date 2026/06/03
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunResult(
        String stop,
        int rounds,
        int toolCalls,
        @JsonInclude(JsonInclude.Include.NON_NULL) String resumeToken,
        @JsonInclude(JsonInclude.Include.NON_NULL) Object ext
) {

    /** 停止原因：正常完成（协议级稳定值，发射方与消费方共用此常量，勿散落字面量）。 */
    public static final String STOP_COMPLETED = "COMPLETED";

    /**
     * 不含续跑句柄的便捷构造:终结类结果（多数终态本就不可续）沿用四参写法,无需感知续跑字段。
     *
     * @param stop      停止原因
     * @param rounds    LLM 循环轮次
     * @param toolCalls 工具调用总次数
     * @param ext       领域扩展结果
     */
    public RunResult(String stop, int rounds, int toolCalls, Object ext) {
        this(stop, rounds, toolCalls, null, ext);
    }

    /**
     * 仅携带停止原因的结果:用于失败 / 拒绝 / 异常等没有完整业务结果的终态。
     *
     * @param stop 停止原因
     * @return 仅含 {@code stop} 的结果,其余为默认值（计数 0、续跑句柄与 {@code ext} 为 null）
     */
    public static RunResult ofStop(String stop) {
        return new RunResult(stop, 0, 0, null, null);
    }
}
