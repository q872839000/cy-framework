package com.cy.ai.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话补全请求模型
 * <p>
 * 对应 OpenAI Chat Completions API 的请求体结构。包含模型选择、消息列表、
 * 采样参数、流式控制等标准字段，以及各厂商的扩展字段容器。
 * </p>
 * <p>
 * 字段填充规则：
 * - 调用方仅设置业务关心的字段，未设置的字段保持 null
 * - Provider 层会根据配置文件中的默认值补齐 null 字段
 * - 序列化时 null 字段不输出（{@code @JsonInclude(NON_NULL)}）
 * </p>
 * <p>
 * 扩展字段用法示例：
 * <pre>{@code
 * ChatCompletionRequest.builder()
 *     .model("qwen3")
 *     .messages(messages)
 *     .build()
 *     .extra("enable_thinking", true);
 * }</pre>
 * </p>
 *
 * @author pcy
 * @date 2026/03/09
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    // ========================= 模型与消息 =========================

    /**
     * 模型标识
     * <p>
     * 对应厂商提供的模型名称，如 "qwen3"、"deepseek-chat"、"glm-4" 等。
     * 为 null 时由 Provider 填充配置中的默认模型。
     * </p>
     */
    private String model;

    /**
     * 消息列表
     * <p>
     * 按时间顺序排列的对话消息，至少包含一条 user 角色消息。
     * 支持 system / user / assistant / tool 四种角色。
     * </p>
     */
    private List<ChatMessage> messages;

    // ========================= 采样参数 =========================

    /**
     * 温度参数（0.0 ~ 2.0）
     * <p>
     * 控制输出的随机性。值越高输出越多样，值越低输出越确定。
     * 通常创意类任务设置 0.7~1.0，分类/提取任务设置 0.0~0.3。
     * </p>
     */
    private Double temperature;

    /**
     * Top-P 核采样参数（0.0 ~ 1.0）
     * <p>
     * 仅从概率累积达到 top_p 的候选 token 中采样。
     * 与 temperature 二选一调整即可，不建议同时调整。
     * </p>
     */
    private Double topP;

    /**
     * 频率惩罚参数（-2.0 ~ 2.0）
     * <p>
     * 正值降低模型重复已出现 token 的倾向，值越大惩罚越强。
     * 适用于需要减少重复输出的场景。
     * </p>
     */
    private Double frequencyPenalty;

    /**
     * 存在惩罚参数（-2.0 ~ 2.0）
     * <p>
     * 正值鼓励模型引入新话题，降低已出现 token 的概率。
     * 与 frequency_penalty 的区别在于：此参数不考虑 token 出现的次数。
     * </p>
     */
    private Double presencePenalty;

    // ========================= 推理控制 =========================

    /**
     * 推理强度（OpenAI 规范标准参数，序列化为 {@code reasoning_effort}）
     * <p>
     * 控制推理类模型思维链的开销档位，规范取值：{@code none} / {@code minimal} /
     * {@code low} / {@code medium} / {@code high} / {@code xhigh}（各模型支持的
     * 子集不一，以其文档为准）。意图分类、字段取值这类机械型调用设为
     * {@code minimal} 可显著缩短时延与 token 消耗。
     * </p>
     * <p>
     * 兼容性约定：本字段是<b>标准通道</b>——遵循 OpenAI 规范的厂商按档位生效；不认识该参数的兼容
     * 端点按惯例忽略未知字段（思考保持模型默认，不报错）；若厂商对标准参数直接报 4xx，属厂商兼容性
     * 缺陷，错误详情会随 {@code CYException} 透传（含厂商响应体），便于定位后在 provider 配置层关闭。
     * 厂商私有的思考开关（如 DashScope 的 {@code enable_thinking}、vLLM 的
     * {@code chat_template_kwargs}）仍走 {@link #extra(String, Object)} 扩展通道。
     * </p>
     */
    private String reasoningEffort;

    /**
     * 思考偏好档位（厂商无关的请求级指令，<b>不序列化</b>、不上 wire）
     * <p>
     * 取值：{@code off}（关闭思考）/ {@code on}（开启，厂商默认预算）/
     * {@code low} / {@code medium} / {@code high}（强度档位）；null 或 {@code auto}
     * 表示不下发请求级偏好，跟随 provider 全局配置（{@code thinking-enabled}）。
     * </p>
     * <p>
     * 翻译由 Provider 在构建 wire 请求时按自身 {@code ThinkingControlMode} 完成
     * （{@code ThinkingPolicyTranslator} 单一来源）：标准模式映射为
     * {@code reasoning_effort} 档位，GLM / DeepSeek / Qwen 等二元开关厂商映射为
     * 对应的思考开关参数（强度档位无法表达时回退为"开启"）。
     * </p>
     */
    @JsonIgnore
    private String thinkingLevel;

    // ========================= 输出控制 =========================

    /**
     * 最大生成 token 数
     * <p>
     * 限制模型单次回复的最大 token 数量。
     * 为 null 时由 Provider 填充配置中的默认值。
     * </p>
     * <p>
     * 注意：OpenAI 已将 {@code max_tokens} 标记为弃用（deprecated），
     * 新一代模型（GPT-5 系列等）只接受 {@code max_completion_tokens}，
     * 对其传 {@code max_tokens} 会直接返回 400；vLLM / DeepSeek 等兼容端点
     * 对两者均支持。面向严格端点请使用 {@link #maxCompletionTokens}。
     * </p>
     */
    private Integer maxTokens;

    /**
     * 最大生成 token 数（含思维链开销，OpenAI 现行标准参数）
     * <p>
     * 序列化为 {@code max_completion_tokens}，是 {@code max_tokens} 的替代参数。
     * 与 {@link #maxTokens} 互斥：两者同时设置时以本字段为准，
     * {@code max_tokens} 不下发（二者同发同样会被严格端点拒绝）。
     * </p>
     */
    private Integer maxCompletionTokens;

    /**
     * 生成候选数量
     * <p>
     * 为每条输入消息生成 n 个候选回复。默认为 1，
     * 多候选场景会成倍消耗 token 配额。
     * </p>
     */
    private Integer n;

    /**
     * 停止序列
     * <p>
     * 模型遇到列表中的任意字符串时停止生成。
     * 最多支持 4 个停止序列。
     * </p>
     */
    private List<String> stop;

    /**
     * 随机种子
     * <p>
     * 固定种子配合相同输入可获得近似确定性的输出，用于结果复现。
     * 注意：并非所有厂商都保证严格确定性。
     * </p>
     */
    private Integer seed;

    /**
     * 响应格式
     * <p>
     * 控制模型输出格式。传入 {@code {"type": "json_object"}} 可强制模型
     * 输出合法 JSON。传入 {@code {"type": "text"}} 或不传则为普通文本。
     * </p>
     */
    private Object responseFormat;

    /**
     * 用户标识
     * <p>
     * 终端用户的唯一标识符，用于厂商侧的滥用监控和用量追踪。
     * 注意：OpenAI 已将本参数标记为弃用（替代参数为 {@code safety_identifier}），
     * 当前仍被各端点广泛接受。
     * </p>
     */
    private String user;

    // ========================= 流式控制 =========================

    /**
     * 是否启用流式输出
     * <p>
     * 为 true 时服务端以 SSE（Server-Sent Events）方式逐 chunk 返回，
     * 为 false 或 null 时一次性返回完整响应。
     * 由 Provider 内部设置，调用方通常不需要手动指定。
     * </p>
     */
    private Boolean stream;

    /**
     * 流式响应附加选项
     * <p>
     * 仅当 {@code stream = true} 时合法——OpenAI / vLLM 对
     * stream=false + stream_options 的组合直接返回 400，
     * 非流式出口（{@code OpenAiClient#chatCompletion}）会自动剔除本字段。
     * 可配置流结束时是否附带 token 用量统计。
     * </p>
     */
    private StreamOptions streamOptions;

    // ========================= 工具调用 =========================

    /**
     * 可用工具定义列表
     * <p>
     * 向模型声明可调用的外部工具（Function Calling）。
     * 每个元素为工具定义对象，包含 type、function 等字段。
     * 使用 Object 类型以兼容不同厂商的工具定义格式。
     * </p>
     */
    private List<Object> tools;

    /**
     * 工具选择策略
     * <p>
     * 控制模型如何选择工具调用：
     * - "auto"：模型自行决定是否调用工具（默认）
     * - "none"：禁止调用工具
     * - "required"：强制调用工具
     * - {"type":"function","function":{"name":"xxx"}}：强制调用指定函数
     * </p>
     */
    private Object toolChoice;

    // ========================= 扩展字段 =========================

    /**
     * 厂商特有的非标准扩展字段
     * <p>
     * 序列化时平铺到 JSON 根级别，反序列化时自动捕获未知字段。
     * 例如 cy-model-server 的 enable_thinking、通义千问的 enable_search 等。
     * </p>
     */
    @Builder.Default
    private Map<String, Object> extraParams = new LinkedHashMap<>();

    /**
     * 获取扩展字段（JSON 序列化时平铺到根级别）
     *
     * @return 扩展字段 Map
     */
    @JsonAnyGetter
    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    /**
     * 捕获反序列化时的未知字段
     *
     * @param key   字段名
     * @param value 字段值
     */
    @JsonAnySetter
    public void setExtraParam(String key, Object value) {
        if (extraParams == null) {
            extraParams = new LinkedHashMap<>();
        }
        extraParams.put(key, value);
    }

    /**
     * 链式设置扩展字段
     * <p>
     * 便于在构建请求后追加厂商特有参数：
     * <pre>{@code
     * request.extra("enable_thinking", true)
     *        .extra("result_format", "message");
     * }</pre>
     * </p>
     *
     * @param key   扩展字段名（对应 API JSON 中的 key）
     * @param value 扩展字段值
     * @return 当前请求对象（链式调用）
     */
    public ChatCompletionRequest extra(String key, Object value) {
        if (extraParams == null) {
            extraParams = new LinkedHashMap<>();
        }
        extraParams.put(key, value);
        return this;
    }
}
