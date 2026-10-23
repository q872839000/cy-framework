// SPDX-License-Identifier: MIT
// Copyright (c) 2026 Orrery Authors
package com.cy.orrery.agent.runtime.context;


import com.cy.orrery.agent.runtime.event.RuntimeEventBus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 运行上下文：单次运行的消息历史、类型安全属性与事件通道。
 * <p>
 * 线程约定：消息列表的写入只发生在引擎线程（循环主线程）；并行工具任务
 * 只回传结果、不直接写消息。属性表并发安全，可跨线程读写。
 * 属性表同时充当跨挂起-恢复的载体：宿主把上下文序列化持久化（如 Redis）时，
 * 暂缓调用、业务动作日志等随属性一并携带。
 * </p>
 *
 * @author pcy
 * @since 2026/08/19
 */
public class AgentRunContext {

    private final String runId;
    private final RuntimeEventBus bus;
    private final CancellationRegistry cancellationRegistry;
    private final List<LoopMessage> messages;
    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final AtomicInteger stepCursor = new AtomicInteger();
    private int compactionBoundary;

    /**
     * 构造运行上下文。
     *
     * @param runId                运行 ID
     * @param bus                  事件总线
     * @param cancellationRegistry 取消注册表（可为 null，表示仅事件总线可取消）
     * @param initialMessages      初始消息（可为 null）
     */
    public AgentRunContext(String runId, RuntimeEventBus bus,
                           CancellationRegistry cancellationRegistry, List<LoopMessage> initialMessages) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.cancellationRegistry = cancellationRegistry;
        this.messages = initialMessages == null ? new ArrayList<>() : new ArrayList<>(initialMessages);
    }

    /**
     * 运行 ID。
     *
     * @return 运行 ID
     */
    public String getRunId() {
        return runId;
    }

    /**
     * 事件总线。
     *
     * @return 事件总线
     */
    public RuntimeEventBus getBus() {
        return bus;
    }

    /**
     * 消息列表（存活视图，引擎线程写入）。
     *
     * @return 消息列表
     */
    public List<LoopMessage> getMessages() {
        return messages;
    }

    /**
     * 追加消息。
     *
     * @param message 消息
     */
    public void addMessage(LoopMessage message) {
        messages.add(Objects.requireNonNull(message, "message"));
    }

    /**
     * 按 callId 覆盖既有 tool 回执消息；不存在则追加（审批暂缓重放覆盖占位回执用）。
     *
     * @param toolCallId 调用 ID
     * @param newContent 新回执正文
     */
    public void replaceOrAddToolMessage(String toolCallId, String newContent) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LoopMessage m = messages.get(i);
            if (LoopMessage.ROLE_TOOL.equals(m.role()) && toolCallId.equals(m.toolCallId())) {
                messages.set(i, LoopMessage.tool(toolCallId, newContent));
                return;
            }
        }
        messages.add(LoopMessage.tool(toolCallId, newContent));
    }

    /**
     * 用哨兵前缀定位并替换上一条同哨兵 user 消息；不存在则追加（重复提示不累积）。
     *
     * @param sentinelPrefix 哨兵前缀
     * @param content        新正文
     */
    public void replaceOrAddUserMessage(String sentinelPrefix, String content) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LoopMessage m = messages.get(i);
            if (LoopMessage.ROLE_USER.equals(m.role())
                    && m.content() != null && m.content().startsWith(sentinelPrefix)) {
                messages.set(i, LoopMessage.user(content));
                return;
            }
        }
        messages.add(LoopMessage.user(content));
    }

    /**
     * 读取属性。
     *
     * @param key 属性键
     * @param <T> 属性值类型
     * @return 属性值；不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttr(AttributeKey<T> key) {
        return (T) attributes.get(key);
    }

    /**
     * 读取属性（带默认值）。
     *
     * @param key          属性键
     * @param defaultValue 默认值
     * @param <T>          属性值类型
     * @return 属性值；不存在返回默认值
     */
    public <T> T getAttr(AttributeKey<T> key, T defaultValue) {
        T value = getAttr(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 写入属性（value 为 null 时移除）。
     *
     * @param key   属性键
     * @param value 属性值
     * @param <T>   属性值类型
     */
    public <T> void putAttr(AttributeKey<T> key, T value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    /**
     * 分配下一个步骤序号（单调递增）。
     *
     * @return 步骤序号
     */
    public int nextStepIndex() {
        return stepCursor.incrementAndGet();
    }

    /**
     * 当前步骤游标值（最近一次分配的序号；未分配过为 0）。
     *
     * @return 游标值
     */
    public int currentStepIndex() {
        return stepCursor.get();
    }

    /**
     * 恢复步骤游标（检查点恢复通道；游标是运行状态的一部分，随检查点往返）。
     *
     * @param value 游标值
     */
    public void restoreStepCursor(int value) {
        stepCursor.set(value);
    }

    /**
     * 属性表快照（检查点序列化用；键为 {@link AttributeKey#name()}）。
     *
     * @return 属性名 → 属性值的副本
     */
    public Map<String, Object> attributeSnapshot() {
        Map<String, Object> snapshot = new HashMap<>();
        attributes.forEach((key, value) -> snapshot.put(key.name(), value));
        return snapshot;
    }

    /**
     * 恢复属性（检查点反序列化通道）。
     * <p>
     * 绕过类型安全键直接按键名写入（{@link AttributeKey} 等价性基于键名，
     * 恢复后与领域持有的强类型键自然匹配）。仅供 CheckpointManager 等
     * 持久化组件使用；值的类型还原由 CheckpointSerializer 的键类型注册表负责，
     * 未注册类型的宿主自定义属性反序列化为通用 Map（宿主需自行后处理）。
     * </p>
     *
     * @param keyName 属性键名
     * @param value   属性值（null 忽略）
     */
    public void restoreAttribute(String keyName, Object value) {
        if (value != null) {
            attributes.put(new AttributeKey<>(keyName, Object.class), value);
        }
    }

    /**
     * 压缩边界：该下标之前的消息曾参与折叠（再次压缩时保持边界单调不回退）。
     *
     * @return 边界下标
     */
    public int getCompactionBoundary() {
        return compactionBoundary;
    }

    /**
     * 设置压缩边界（只增不减）。
     *
     * @param boundary 边界下标
     */
    public void setCompactionBoundary(int boundary) {
        this.compactionBoundary = Math.max(this.compactionBoundary, boundary);
    }

    /**
     * 重置压缩边界（非单调，允许调小 / 归零）。
     * <p>
     * 用于宿主在清空消息历史重建会话视图（如引擎切换）后重置边界——
     * 此时旧边界对新消息列表已无意义，必须允许回退，否则边界越界。
     * 常规压缩推进仍应使用只增的 {@link #setCompactionBoundary}。
     * </p>
     *
     * @param boundary 边界下标
     */
    public void resetCompactionBoundary(int boundary) {
        this.compactionBoundary = boundary;
    }

    /**
     * 用哨兵前缀定位并替换上一条同哨兵 user 消息；不存在则追加。
     * 与 {@link #replaceOrAddUserMessage} 的差异：命中时把新正文<b>追加</b>到既有正文尾部
     * （以换行分隔），而非整体替换——用于宿主在保留原提示的前提下叠加补充说明。
     *
     * @param sentinelPrefix 哨兵前缀
     * @param content        追加正文
     */
    public void replaceOrAppendUserMessage(String sentinelPrefix, String content) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            LoopMessage m = messages.get(i);
            if (LoopMessage.ROLE_USER.equals(m.role())
                    && m.content() != null && m.content().startsWith(sentinelPrefix)) {
                messages.set(i, LoopMessage.user(m.content() + "\n" + content));
                return;
            }
        }
        messages.add(LoopMessage.user(content));
    }

    /**
     * 取消检查：事件总线取消 或 取消注册表命中 任一即视为取消。
     *
     * @return true 表示已取消
     */
    public boolean isCancelled() {
        return bus.isCancelled()
                || (cancellationRegistry != null && cancellationRegistry.isCancelled(runId));
    }
}
