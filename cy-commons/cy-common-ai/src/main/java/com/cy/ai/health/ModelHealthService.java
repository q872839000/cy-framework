package com.cy.ai.health;

import com.cy.ai.AiFacade;
import com.cy.ai.model.ChatCompletionRequest;
import com.cy.ai.model.ChatCompletionResponse;
import com.cy.ai.model.ChatMessage;
import com.cy.ai.thinking.ThinkingPolicy;
import com.cy.ai.thinking.ThinkingPolicyTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 大模型可用性健康检查服务。
 * <p>
 * 后台常驻维护一个"可用 / 不可用"布尔状态,对外只暴露 {@link #check()} 供健康检查接口调用,
 * 让前端 HTTP 轮询判断大模型是否就绪。状态来源三条路径:
 * <ul>
 *     <li><b>启动自检</b>:服务就绪后立即探一次,首个结果(含不可用)即写入缓存</li>
 *     <li><b>定时刷新</b>:每 30 分钟重探一次,可用 / 不可用都刷新,用于发现"由可用转不可用"</li>
 *     <li><b>按需补探</b>:{@link #check()} 在当前不可用时当场再探一次,模型恢复后即时放行</li>
 * </ul>
 * </p>
 * <p>
 * <b>并发约定:</b>{@link #refresh()} 与 {@link #check()} 都 {@code synchronized} 在本实例锁上,
 * 三条路径永远只有一个探测在跑——既不会并发打爆模型,也不会出现状态读写竞争。
 * {@code available} 全程在锁内读写,因而无需 {@code volatile}。
 * </p>
 *
 * @author pcy
 */
public class ModelHealthService {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthService.class);

    /** 探测话术:极简单词,只为引一次合法回复,不关心正文内容。 */
    private static final String PROBE_MESSAGE = "你好";

    /** 探测输出额度:取最小合法值,只验证连通性不要正文。 */
    private static final int PROBE_MAX_TOKENS = 1;

    /** 定时刷新间隔(分钟):无论当前可用与否都重探。 */
    private static final long REFRESH_INTERVAL_MINUTES = 30;

    /**
     * 不可用状态下按需补探的冷却时间(毫秒)。
     * <p>
     * 前端轮询健康检查时,若模型持续不可用,每次 check 都现场 ping 会让请求在 synchronized 锁上
     * 串行排队(每个等待超时),拖住 Web 线程。冷却期内直接返回缓存的 false,避免放大效应。
     * 定时刷新不受此限制,仍会按固定周期强制重探。
     * </p>
     */
    private static final long UNAVAILABLE_PROBE_COOLDOWN_MS = 10_000L;

    private final AiFacade aiFacade;
    private final ModelHealthProperties properties;

    /** 缓存的可用状态。仅在 synchronized 方法内读写,故不需要 volatile。 */
    private boolean available = false;

    /** 上次按需补探的时间戳,用于 cooldown 判定。仅在 synchronized 方法内读写。 */
    private long lastProbeTime = 0L;

    public ModelHealthService(AiFacade aiFacade, ModelHealthProperties properties) {
        this.aiFacade = aiFacade;
        this.properties = properties;
    }

    /**
     * 极简探测:发一句"你好"、{@code max_tokens=1}、关思考、{@link ModelHealthProperties#getTimeout() 配置超时}。
     * <p>
     * 因为已关思考且只取 1 个 token,探测不会陷入长推理,首 token 延迟即决定耗时;超时仅用于兜底冷启动 / 排队。
     * 拿到任意结构完整的回复即判活——<b>不看正文、不看 finish_reason</b>
     * ({@code max_tokens=1} 必然 length 截断,那也是模型活着)。任何异常 / 超时一律判死且绝不抛出,
     * 因为"探测失败"本身就是要表达的"不可用"信号(超时会取消底层 HTTP 调用,不留悬挂请求)。
     * </p>
     *
     * @return 模型可用返回 true,否则 false
     */
    private boolean ping() {
        try {
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .messages(List.of(ChatMessage.user(PROBE_MESSAGE)))
                    .maxTokens(PROBE_MAX_TOKENS)
                    .build();
            // 关思考：按默认 provider 的思考控制模式翻译为厂商对应的 wire 参数
            ThinkingPolicyTranslator.apply(
                    ThinkingPolicy.DISABLED,
                    aiFacade.getDefaultProviderThinkingControlMode(),
                    request);
            ChatCompletionResponse response = aiFacade.chat(request).block(properties.getTimeout());
            return response != null
                    && response.getChoices() != null
                    && !response.getChoices().isEmpty();
        } catch (Exception e) {
            log.warn("大模型健康探测失败,标记为不可用: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 强制探测一次并刷新缓存(忽略当前状态)。启动自检与定时刷新调用。
     * <p>定时刷新必须强制重探,这样才能抓到一个"看着可用、其实已挂"的模型,不能复用
     * {@link #check()} 的"可用就跳过"逻辑。不更新 {@link #lastProbeTime} ——
     * cooldown 只约束 {@link #check()} 的按需补探,不应阻塞定时刷新后的首次 check。</p>
     */
    public synchronized void refresh() {
        boolean current = ping();
        if (current != available) {
            log.info("大模型可用状态变化: {} -> {}", available, current);
        }
        available = current;
    }

    /**
     * 供健康检查接口使用:已可用直接返回;不可用时按需补探,但受 cooldown 约束。
     * <p>
     * 与 {@link #refresh()} 同锁,并发查询自然串行,不会并发打爆模型。不可用时的补探有
     * {@link #UNAVAILABLE_PROBE_COOLDOWN_MS} 冷却窗口 —— 上次 check 补探失败后,
     * 冷却期内直接返回 false,避免前端高频轮询导致 Web 线程在 synchronized 锁上
     * 串行排队(每个等待探测超时)。首次 check(刚启动或 refresh 后)不受 cooldown 限制。
     * </p>
     *
     * @return 最新可用状态
     */
    public synchronized boolean check() {
        if (!available) {
            long now = System.currentTimeMillis();
            if (now - lastProbeTime < UNAVAILABLE_PROBE_COOLDOWN_MS) {
                return false;
            }
            lastProbeTime = now;
            available = ping();
        }
        return available;
    }

    /**
     * 启动自检。
     * <p>监听 {@link ApplicationReadyEvent}——此时上下文已刷新、Web 端口已就绪,本方法只是把首个探测结果
     * 写入缓存,不阻塞服务启动与对外提供能力。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        refresh();
    }

    /**
     * 定时刷新:每 {@value #REFRESH_INTERVAL_MINUTES} 分钟重探一次,可用 / 不可用都刷新。
     * <p>
     * <b>初始延迟</b>设为与刷新间隔相同(30 分钟)——启动期探测由
     * {@link #onApplicationReady()} 独占,避免两者同时触发导致刚启动就双重探测、
     * 两次结果矛盾(如一次成功一次超时)造成状态来回翻转。
     * </p>
     * <p>依赖宿主应用开启 {@code @EnableScheduling}。</p>
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MINUTES, initialDelay = REFRESH_INTERVAL_MINUTES, timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        refresh();
    }
}
