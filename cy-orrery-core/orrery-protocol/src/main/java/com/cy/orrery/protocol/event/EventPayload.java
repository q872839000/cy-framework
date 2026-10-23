// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.protocol.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 事件负载（Agent 事件协议 v1）
 * <p>
 * 信封 {@link AgentEvent} 只承载公共元数据,各事件类型专属字段一律收进本负载并整体放进信封的 {@code data} 段。
 * 每个负载 record 通过 {@link #type()} 声明自己对应的 {@link EventType},由此在编译期把“负载结构”与“事件类型”
 * 绑定——既杜绝 type 与字段错配,又让信封工厂能从负载反推 type / channel / terminal,无需调用方手填;反向由
 * {@link EventType#payloadType()} 支撑 {@link AgentEventDeserializer} 把 {@code data} 段还原为强类型。
 * </p>
 * <p>
 * 设计取舍:
 * <ul>
 *     <li>闭集字段一律枚举（{@link Phase} / {@link BlockType}）,稳定契约字段一律强类型子记录
 *         （如 {@link RunResult}）；</li>
 *     <li>本质开放的字段保留弹性类型并在此显式说明:里程碑负载随 {@code kind} 变化、系统追踪元数据、
 *         工具入参 / 出参、原始 chunk 文本、以及领域扩展 {@code ext}——强行收敛类型反而错误。
 *         开放字段在内核中一律声明为 {@code Object}:生产侧应传<b>强类型对象</b>（同一 {@code kind} /
 *         {@code category} 下形状是固定的,由各 Agent 在自己的模块里定义,wire 上同为 JSON object）,
 *         消费侧反序列化后为 {@code Map};内核绝不因开放性而把生产代码逼回“Map 当结构体”；</li>
 *     <li>{@link Unknown} 是前向兼容兜底:遇到本版本不认识的类型时原样保留字段,中继 / 跨版本不丢负载。</li>
 * </ul>
 * 本接口为 <b>sealed 封闭内核</b>:新增负载类型属于协议规范演进,不向单个 Agent 开放;领域差异一律走
 * {@link DomainData}（运行途中）与 {@link RunResult#ext()}（终态）两个扩展槽。
 * </p>
 * <p>
 * 类级 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 由所有负载继承:同一规范 major 内“新增可选字段”
 * 是兼容性演进,老消费者用任意 {@link com.fasterxml.jackson.databind.ObjectMapper}（含默认开启
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 的裸 mapper）反序列化新版负载时忽略未知字段而非失败——否则规范的
 * 前向兼容承诺会被消费端 mapper 配置左右。{@link Unknown} 的 {@code @JsonAnySetter} 优先于忽略策略,兜底语义不受影响。
 * </p>
 *
 * @author pcy
 * @date 2026/06/03
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface EventPayload
        permits EventPayload.StageChanged, EventPayload.Progress, EventPayload.Milestone,
                EventPayload.Clarification, EventPayload.ApprovalRequired, EventPayload.BusinessError,
                EventPayload.Final, EventPayload.Cancelled,
                EventPayload.RunFailed, EventPayload.StepStarted, EventPayload.StepFinished,
                EventPayload.BlockStart, EventPayload.BlockDelta, EventPayload.BlockEnd,
                EventPayload.Heartbeat, EventPayload.DomainData,
                EventPayload.ToolCallStart, EventPayload.ToolCallEnd,
                EventPayload.LlmCall, EventPayload.RawLlmChunk, EventPayload.SystemTrace,
                EventPayload.Unknown {

    /**
     * 本负载对应的事件类型,决定信封的 {@code type} / {@code channel} / {@code terminal}（成功/取消/失败由 {@code type} 区分,信封不设 outcome 字段）。
     * <p>不序列化进 {@code data}(信封顶层已有 {@code type} 字段)。</p>
     *
     * @return 事件类型
     */
    @JsonIgnore
    EventType type();

    // ========================= 用户频道:业务语义事件 =========================

    /**
     * 处理阶段推进
     * <p>
     * Agent 编排链路进入 / 完成某个处理阶段时发射的<b>确定性</b>事件,与模型思维链（reasoning 块）严格区分:
     * 由编排代码在确定的节点发射,不依赖模型输出。
     * </p>
     *
     * @param stage  阶段机器标识（如 {@code "routing"}、{@code "planning"}、{@code "validation"}）
     * @param title  阶段显示名（如 “意图路由”）
     * @param detail 该阶段的结论或说明,可为空
     */
    record StageChanged(String stage, String title, String detail) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.STAGE_CHANGED;
        }
    }

    /**
     * 阶段进度
     *
     * @param phase   阶段标识（如 {@code "planning"}、{@code "executing"}）
     * @param current 当前进度
     * @param total   总步数（未知时为 0,前端不显示百分比）
     * @param message 进度描述
     */
    record Progress(String phase, int current, int total, String message) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.PROGRESS;
        }
    }

    /**
     * 里程碑事件
     *
     * @param kind    里程碑类型（如 {@code "plan_drafted"}、{@code "quality_check"}）
     * @param title   标题
     * @param payload 结构化负载。<b>本质开放</b>:形状随 {@code kind} 变化,前端按 {@code kind} 分支解析。
     *                同一 {@code kind} 下形状固定,生产侧应传强类型对象(由各 Agent 定义,wire 为 JSON object),
     *                反序列化后 Java 侧为 {@code Map}
     */
    record Milestone(String kind, String title, Object payload) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.MILESTONE;
        }
    }

    /**
     * 澄清请求
     * <p>
     * {@code kind} 判别澄清语义,前端按其选择交互形态（信息补充输入框 / 续跑确认 / 裁定选项卡）;
     * 缺省 {@link #KIND_INFO}（兼容未携带 kind 的既有载荷与发射方）。
     * </p>
     *
     * @param question    追问内容
     * @param suggestions 可选建议
     * @param kind        澄清语义判别（{@link #KIND_INFO} / {@link #KIND_CONTINUATION} / {@link #KIND_DECISION}）;
     *                    {@code null}/空白时规范化为 {@link #KIND_INFO}
     */
    record Clarification(String question, List<String> suggestions, String kind) implements EventPayload {

        /** 信息补充：常规澄清提问（缺省语义，兼容存量载荷与发射方）。 */
        public static final String KIND_INFO = "INFO";

        /** 续跑确认：达到循环/预算上限后的"是否继续"提示（如 MAX_STEPS 续跑）。 */
        public static final String KIND_CONTINUATION = "CONTINUATION";

        /** 业务裁定：质量门/约束核验升级用户裁决的抉择请求（选项即处置决定）。 */
        public static final String KIND_DECISION = "DECISION";

        /** 计划确认：L2/L3 计划草案已就绪，等用户确认后开始执行（渲染计划确认卡片）。 */
        public static final String KIND_PLAN_CONFIRMATION = "PLAN_CONFIRMATION";

        /**
         * 兼容构造：未指定语义时按 {@link #KIND_INFO} 信息补充处理。
         *
         * @param question    追问内容
         * @param suggestions 可选建议
         */
        public Clarification(String question, List<String> suggestions) {
            this(question, suggestions, KIND_INFO);
        }

        /**
         * 规范化：{@code kind} 为 {@code null}/空白时落 {@link #KIND_INFO},
         * 保证 wire 上既有载荷（无 kind 字段）反序列化后语义明确。
         */
        public Clarification {
            kind = kind == null || kind.isBlank() ? KIND_INFO : kind;
        }

        @Override
        public EventType type() {
            return EventType.CLARIFICATION;
        }
    }

    /**
     * 待审批
     *
     * @param approvalId 审批单 ID
     * @param summary    审批摘要
     * @param diff       预期变更。<b>本质开放</b>:不同审批形状不同。同一审批类型下形状固定,
     *                   生产侧应传强类型对象,反序列化后 Java 侧为 {@code Map}
     */
    record ApprovalRequired(String approvalId, String summary, Object diff)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.APPROVAL_REQUIRED;
        }
    }

    /**
     * 业务错误(非终态)
     * <p>用于可继续 / 可重试的错误与风险提示;<b>真正终止本次运行</b>的失败请用 {@link RunFailed}（terminal）。</p>
     *
     * @param code      错误码
     * @param message   错误描述
     * @param retryable 是否可重试
     */
    record BusinessError(String code, String message, boolean retryable) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.BUSINESS_ERROR;
        }
    }

    /**
     * 终态事件(正常完成)
     * <p>
     * {@code terminal=true};成功 / 取消 / 失败直接由 {@code type} 区分（信封不设 outcome 字段）,与 {@link Cancelled} / {@link RunFailed} 三者互斥。
     * </p>
     * <p>
     * <b>纯终态标识,不携带正文。</b>模型面向用户的答复一律在终止前由 {@code phase=final} 的
     * {@link BlockType#TEXT} 块流式承载(与思维链 {@link BlockType#REASONING} 块同一套块协议);
     * 消费者拼接该相位的 text 块即得完整答复。本事件只回答"运行已结束",并经 {@link #result} 携带通用
     * 运行遥测与领域结果(见 {@link RunResult#ext()})。如此杜绝"正文既流式下发又在终态整段重发"的
     * 双事实源,使任意路径(闲聊 / ReAct / 工作流)的终态形状一致。
     * </p>
     *
     * @param result 通用运行结果（强类型内核 + 领域扩展,见 {@link RunResult}）
     */
    record Final(RunResult result) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.FINAL;
        }
    }

    /**
     * 取消终态事件
     * <p>用户协作式中止,{@code terminal=true},取消语义由 {@code type=Cancelled} 表达（信封无 outcome 字段）;不是错误（区别于 {@link RunFailed}）。</p>
     *
     * @param reason      面向用户的中止说明
     * @param phase       取消发生时所处阶段标识（排障用）
     * @param resumeToken 续跑句柄:中断时已有在途产物（已提交动作 / 半成品阶段）时非 null,消费端持有并下一轮回传可续;无可续内容时为 null
     */
    record Cancelled(String reason, String phase,
                     @JsonInclude(JsonInclude.Include.NON_NULL) String resumeToken) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.CANCELLED;
        }
    }

    /**
     * 失败终态事件
     * <p>
     * {@code terminal=true},失败语义由 {@code type=RunFailed} 表达（信封无 outcome 字段）:把“本次运行失败收尾”
     * 一等化为终态事件,使消费者凭 {@code terminal=true} 即可干净结束、再据 {@code type} 区分失败,不必依赖
     * “SSE 流被动关闭”这一传输层信号,也不必把某个非终态错误猜成结束。与 {@link BusinessError}（非终态、可继续）区分清楚。
     * </p>
     *
     * @param code      失败码（如 {@code AGENT_RUN_FAILED} / {@code SERVICE_BUSY}）
     * @param message   面向用户的失败说明
     * @param retryable 是否可重试
     * @param result    运行结果（通常仅含停止原因,见 {@link RunResult#ofStop(String)}）
     */
    record RunFailed(String code, String message, boolean retryable, RunResult result)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.RUN_FAILED;
        }
    }

    // ========================= 用户频道:步骤与块生命周期 =========================

    /**
     * 步骤开始
     * <p>Agent 主循环每调用一次 LLM 即为一个 step;前端据 {@code stepIndex} 把同一 step 的块归为可折叠工作区。</p>
     *
     * @param stepIndex 步骤序号（从 0 起）
     */
    record StepStarted(int stepIndex) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.STEP_STARTED;
        }
    }

    /**
     * 步骤结束(正文相位结算点)
     *
     * @param stepIndex     步骤序号
     * @param hadToolCalls  本轮是否以工具调用收尾
     * @param terminalPhase 本轮正文块相位（{@link Phase#COMMENTARY} / {@link Phase#FINAL}）
     */
    record StepFinished(int stepIndex, boolean hadToolCalls, Phase terminalPhase)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.STEP_FINISHED;
        }
    }

    /**
     * 流式块开始
     *
     * @param stepIndex 所属步骤序号
     * @param blockId   块唯一 ID
     * @param blockType 块类型（{@link BlockType#REASONING} / {@link BlockType#TEXT}）
     * @param label     该块的阶段标识 / 显示名（如“意图分析”“方案规划”），供前端区分一轮里的多段思考;
     *                  无特定阶段时为 null（如通用思考、正文块）
     */
    record BlockStart(int stepIndex, String blockId, BlockType blockType,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String label) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.BLOCK_START;
        }
    }

    /**
     * 流式块增量
     * <p>
     * 冗余携带 {@code stepIndex} 与 {@code blockType}(不止 {@code blockId}),使断点续传从块中途接入时,
     * 客户端无需依赖 {@link BlockStart} 即可正确归组与渲染孤儿增量。
     * </p>
     *
     * @param stepIndex 所属步骤序号
     * @param blockId   块唯一 ID
     * @param blockType 块类型
     * @param delta     本次增量文本
     */
    record BlockDelta(int stepIndex, String blockId, BlockType blockType, String delta)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.BLOCK_DELTA;
        }
    }

    /**
     * 流式块结束
     *
     * @param stepIndex 所属步骤序号
     * @param blockId   块唯一 ID
     * @param blockType 块类型
     */
    record BlockEnd(int stepIndex, String blockId, BlockType blockType) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.BLOCK_END;
        }
    }

    // ========================= 用户频道:保活 =========================

    /**
     * 心跳保活
     * <p>
     * 长时间无业务事件时（典型:单次长推理、HITL 审批等待）周期性发射,维持 SSE 活跃、防止中间代理按空闲
     * 超时断流。无业务语义,消费者可仅用于刷新“仍在处理”状态或直接忽略。
     * </p>
     *
     * @param uptimeMs 本次运行已运行时长（毫秒）
     */
    record Heartbeat(long uptimeMs) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.HEARTBEAT;
        }
    }

    // ========================= 用户频道:领域流式数据 =========================

    /**
     * 通用领域流式数据(Agent 事件协议 v1 的<b>领域扩展槽</b>,非内核语义)
     * <p>
     * 与终态的 {@link RunResult#ext()} 对称——一个承载"运行结束的领域结果",一个承载"运行途中的领域数据"。
     * 内核不认识任何具体领域:{@code kind} 是领域事件的命名空间(建议 {@code <domain>.<event>} 形式,
     * 如 {@code "orders.delta"}),{@code ext} 是该领域的强类型负载(本质开放,消费端按 {@code kind} 反序列化,
     * 如 {@code objectMapper.convertValue(ext, XxxDelta.class)})。如此让"统一 Agent 协议"无需为某个 Agent 的
     * 领域流式数据新增内核类型——换一个 Agent 复用同一个 {@code DomainData},只换 {@code kind} 与 {@code ext} 形状。
     * </p>
     *
     * @param kind 领域事件命名空间(建议 {@code <domain>.<event>} 形式)
     * @param ext  领域强类型负载(本质开放,可为 null);由发射方 Agent 定义其形状
     */
    record DomainData(String kind, Object ext) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.DOMAIN_DATA;
        }
    }

    // ========================= 调试频道:诊断事件 =========================

    /**
     * 工具调用开始
     *
     * @param callId    工具调用 ID
     * @param tool      工具名
     * @param arguments 入参。<b>本质开放</b>:不同工具入参结构不同
     * @param narration 可选的人类可读叙述
     */
    record ToolCallStart(String callId, String tool, Object arguments, String narration)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.TOOL_CALL_START;
        }
    }

    /**
     * 工具调用结束
     *
     * @param callId     工具调用 ID
     * @param tool       工具名
     * @param success    是否成功
     * @param result     返回结果。<b>本质开放</b>:不同工具出参结构不同;成功时常含 {@code message}
     * @param durationMs 耗时（毫秒）
     * @param error      错误信息,成功时为 null
     */
    record ToolCallEnd(String callId, String tool, boolean success, Object result,
                       long durationMs, String error) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.TOOL_CALL_END;
        }
    }

    /**
     * LLM 调用汇总
     *
     * @param callId           LLM 调用 ID
     * @param model            使用的模型
     * @param promptTokens     提示 token 数
     * @param completionTokens 补全 token 数
     * @param latencyMs        耗时（毫秒）
     * @param finishReason     生成结束原因（OpenAI 协议线上值：{@code stop} / {@code tool_calls} /
     *                         {@code length}（输出达到 max_tokens 上限被截断）/ {@code content_filter}；
     *                         厂商漏发时为 null）
     */
    record LlmCall(String callId, String model, int promptTokens, int completionTokens,
                   long latencyMs, String finishReason) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.LLM_CALL;
        }
    }

    /**
     * 原始 Chunk
     *
     * @param callId LLM 调用 ID
     * @param json   原始 JSON 文本（本质开放）
     */
    record RawLlmChunk(String callId, String json) implements EventPayload {
        @Override
        public EventType type() {
            return EventType.RAW_LLM_CHUNK;
        }
    }

    /**
     * 系统追踪
     *
     * @param category 分类（如 {@code "agent.start"}、{@code "agent.end"}）
     * @param message  描述
     * @param meta     附加元数据。<b>本质开放</b>:不同 category 形状不同。调试频道遥测以人读为主,
     *                 此处允许直接传 {@code Map}（用户频道的开放槽则应传强类型对象）
     */
    record SystemTrace(String category, String message, Object meta)
            implements EventPayload {
        @Override
        public EventType type() {
            return EventType.SYSTEM_TRACE;
        }
    }

    // ========================= 前向兼容兜底 =========================

    /**
     * 未知负载(前向兼容兜底)
     * <p>
     * 当反序列化遇到本版本枚举里不存在的事件类型时,{@link AgentEventDeserializer} 用本类承接 {@code data} 段:
     * {@code @JsonAnySetter} 原样吸收所有字段、{@code @JsonAnyGetter} 原样吐回,保证中继转发 / 跨版本消费时
     * 负载不丢失。它对应 {@link EventType#UNKNOWN},不参与发射 / 工厂。
     * </p>
     * <p>非 record(record 不便配合 {@code @JsonAnySetter} 的可变收集),刻意保留为可被 sealed 接口 permit 的 final 类。</p>
     */
    final class Unknown implements EventPayload {

        private final Map<String, Object> properties = new LinkedHashMap<>();

        /** 吸收所有未知字段。 */
        @JsonAnySetter
        public void put(String key, Object value) {
            properties.put(key, value);
        }

        /** 原样吐回吸收到的字段(序列化时平铺回 {@code data} 段)。 */
        @JsonAnyGetter
        public Map<String, Object> properties() {
            return properties;
        }

        @Override
        @JsonIgnore
        public EventType type() {
            return EventType.UNKNOWN;
        }
    }
}
