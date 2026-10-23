# orrery-agent-runtime

领域无关的通用 Agent 运行时框架：ReAct 循环引擎 + 阶段链工作流引擎 + 生产级护栏（熔断 / 超时 / 上下文守望）+ HITL 人机协同 + 检查点持久化。供 Orrery 生态内所有 Agent 复用。

## 能力总览

| 能力         | 组件                                         | 说明                                                                                    |
| ---------- | ------------------------------------------ | ------------------------------------------------------------------------------------- |
| ReAct 执行内核 | `loop/ReactLoopEngine`                     | 思考→工具→观察主循环；收敛/退化双熔断、小步走检测、轮次耗尽终局                                                     |
| 阶段链工作流     | `pipeline/PipelineEngine`                  | sealed `StageOutcome` 八态流转、回退预算、转移熔断、引擎热切换                                            |
| 工具轮编排      | `loop/LoopToolRoundEngine`                 | 审批屏障、确定性重放、部分失败语义、只读调用并行执行                                                            |
| 工具护栏       | `tool/GuardedToolInvoker`                  | 超时强取消、参数指纹熔断、失败连击升级                                                                   |
| 上下文工程      | `harness/DefaultContextGuard`              | token 估算、折叠压缩、钉扎保留、边界单调（守望判定与折叠委托 orrery-memory 公共上下文子系统）                             |
| HITL       | `hitl/*`                                   | 审批门（SPI，memory / redis 双实现）、挂起态契约、决定注入合成回执、决定存储兜底                                     |
| 检查点持久化     | `checkpoint/*`                             | CheckpointStore SPI + InMemory / Redis 实现 + durability 三档（SYNC/ASYNC/EXIT）            |
| 跨运行记忆      | `bridge/memory/*`                          | MemoryRunBridge（运行态写入 orrery-memory）、MemoryCheckpointStore（记忆后端检查点存储）                 |
| 运行门面       | `runner/AgentRunner`                       | 运行生命周期一体化：终态事件、挂起持久化、检查点恢复、审批续跑                                                       |
| LLM 适配     | `llm/*` + `bridge/cyai/AiFacadeLlmInvoker` | 重试、截断续接、结构化输出（JSON/Schema + 解析重试）、cy-common-ai 生产桥                                    |
| 事件体系       | `event/*`                                  | 13 类冻结事件、TEXT\_DELTA 流式、`bridge/protocol/AgentEventBridge` 桥接 Agent Event Protocol v1 |
| 观测         | `observe/MicrometerRuntimeObserver`        | 基于事件的 Micrometer 埋点（runs/tools/fuses/tokens）                                          |
| 意图路由       | `intent/IntentRouter`                      | 意图键 → 策略分发（引擎选择）                                                                      |
| 计划账本       | `plan/PlanLedger`                          | 显式计划勾稽推进、全终态提前收敛                                                                      |

> ⚠️ **审批屏障契约（先执行后判定）**：审批 / 澄清判定以工具**执行结果**为输入——触发审批的调用在判定前已真实执行，屏障只拦同轮后续兄弟调用（暂缓待批，批准后确定性重放）。被审批工具必须以「无副作用回执」响应（预演形态），真实落库动作留到重放，"拒绝"才等价于"不执行"。

**跨运行记忆前置条件**（`bridge/memory/*`，选配能力，由业务方决定是否启用）：

- `MemoryRunBridge` 推荐用全参构造（传入 `MemoryManager`）——构造期按 `capabilities()` 校验巩固/摘要所需层，缺口打一次性 WARN；运行期按业务方实际开启的层执行，目标层未开启时跳过对应动作（用户私有巩固不降级落共享层）；
- `MemoryCheckpointStore` 构造期 fail-fast 要求 WORKING 层开启（50503）；**WORKING 层默认 TTL 1 小时**，用于跨天审批挂起恢复时须将 `cy.orrery.memory.layers.working.ttl-seconds` 调至 ≥ 挂起恢复窗口（对齐 Redis 存储的 suspended-ttl 语义，默认 7 天）。

## 如何使用

### Maven 依赖

```xml
<!-- 依赖经 cy-orrery-bom 统一管理版本（推荐） -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.cy.orrery</groupId>
            <artifactId>cy-orrery-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>com.cy.orrery</groupId>
    <artifactId>orrery-agent-runtime</artifactId>
</dependency>
```

引入本模块自动传递的依赖（选配能力按类路径条件装配，缺失不报错）：

| 传递依赖              | 用途                                                     | 传递性 |
| ----------------- | ------------------------------------------------------ | ---- |
| `orrery-protocol` | AgentEvent 线协议桥（生态线协议标准）                               | 必需 |
| `orrery-memory`   | 跨运行记忆桥 + 公共上下文子系统（上下文守望/折叠委托其实现）                       | 必需 |
| `cy-common-ai`    | 生产 LLM 适配器 `AiFacadeLlmInvoker`（宿主手动构造；不用该桥的宿主不引入 webflux 栈） | optional |
| `redisson`        | Redis 检查点存储 / Redis 审批门（gate/checkpoint=redis 时装配；不用 Redis 的宿主不引入 netty 栈） | optional |

按需引入（不引则对应能力不装配，其余不受影响）：

```xml
<dependency>  <!-- 可选：Micrometer 观测 -->
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

### 最小 ReAct Agent（20 行核心）

```java
// 1. 领域适配：实现两个 SPI
ToolInvoker tools = (callId, name, args, ctx) -> myTools.execute(name, args);
LlmInvoker llm = new AiFacadeLlmInvoker(aiFacade, myToolSpecs);

// 2. 组装引擎（无状态，可单例复用）
LoopToolRoundEngine toolRound = new LoopToolRoundEngine(tools, myInspector, objectMapper,
        Executors.newVirtualThreadPerTaskExecutor());  // 只读调用并行
ReactLoopEngine engine = new ReactLoopEngine(toolRound);

// 3. 运行（AgentRunner 托管：终态事件 + 检查点 + 挂起恢复）
AgentRunContext ctx = runner.startContext(runId, List.of(LoopMessage.user(userInput)));
LoopOutcome outcome = runner.runReact(engine, ctx,
        LoopRuntimeSpec.of(LoopConfig.defaults(), llm, myDomainSpec, contextGuard, myHooks));

// 4. 审批挂起？持久化已在后台完成；决定到达后续跑
if (outcome.stop() == AgentStop.NEEDS_APPROVAL) {
    // ……任意时刻（哪怕进程重启后）：
    AgentRunContext restored = runner.restoreContext(runId).orElseThrow();
    outcome = runner.awaitApprovalsAndResume(engine, restored, spec, 60_000);
}
```

### Spring Boot 自动装配

引入依赖后自动注册（全部 `@ConditionalOnMissingBean`，可覆盖）：

| Bean                                                                               | 默认实现                                                                        | 覆盖方式     |
| ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------- | -------- |
| `CancellationRegistry`                                                             | 进程内                                                                         | 自定义 Bean |
| `ApprovalGate`                                                                     | 进程内 / Redis（`cy.orrery.agent-runtime.approval.gate=redis`，需 RedissonClient） | 自定义 Bean |
| `ApprovalDecisionStore`                                                            | 进程内（「决定先于等待到达」兜底）                                                           | 共享存储实现   |
| `ContextGuard`                                                                     | 配置驱动压缩                                                                      | 自定义 Bean |
| `PipelineEngine`                                                                   | 单例                                                                          | —        |
| `CheckpointStore`                                                                  | 内存 / Redis（`cy.orrery.agent-runtime.checkpoint.store=redis`）                | 自定义 Bean |
| `CheckpointManager` / `AgentRunner` / `HitlCoordinator` / `RuntimeEventBusFactory` | 自动                                                                          | —        |
| `MicrometerRuntimeObserver`                                                        | 有 MeterRegistry 时自动挂载                                                       | —        |
| `AgentEventBridge`                                                                 | 有 AgentEventPublisher Bean 时自动挂载                                            | —        |

引擎实例（`ReactLoopEngine` / `LoopToolRoundEngine` / `GuardedToolInvoker` / LLM 装饰器）依赖宿主领域 SPI，由宿主在装配层构造，不由自动配置注册。

### SPI 扩展点（宿主接入面）

| SPI                                          | 职责                                   | 必须实现？                      |
| -------------------------------------------- | ------------------------------------ | -------------------------- |
| `LlmInvoker`                                 | LLM 调用归一化（流式 TEXT\_DELTA、token、失败语义） | 是（或用 `AiFacadeLlmInvoker`） |
| `ToolInvoker`                                | 工具执行边界（错误三分：可自纠/不可自纠/部分失败）           | 是                          |
| `LoopDomainSpec`                             | 无工具轮处理、状态签名、终局结算、熔断文案                | 是（有默认实现的方法居多）              |
| `ToolRoundHooks`                             | 业务动作收集、审批挂起发射权、回执文案                  | 否（NO\_OP 可用）               |
| `ToolCallInspector`                          | 工具结果 → 审批/澄清判定                       | 否（NoOp 默认）                 |
| `StageHandler`                               | 阶段链各阶段语义                             | 用阶段链时是                     |
| `ApprovalGate`                               | 审批等待门（多副本需分布式实现）                     | 否（内存默认）                    |
| `CheckpointStore`                            | 检查点存储                                | 否（内存默认；多副本用 Redis）         |
| `CheckpointSerializer.registerAttributeType` | 宿主自定义属性的强类型往返                        | 有自定义属性时                    |
| `ContextGuard` / `MessagePinner`             | 上下文守望与钉扎                             | 否（默认实现）                    |
| `AgentEventPublisher`                        | AgentEvent 下发通道（SSE/Redis/MQ）        | 用线协议桥时                     |

## 配置项

全部配置位于 `cy.orrery.agent-runtime.*`，均有默认值，零配置可用：

```yaml
cy:
  orrery:
    agent-runtime:
      loop:
        max-rounds: 25                      # ReAct 最大轮次
        max-consecutive-failed-rounds: 3    # 退化熔断阈值（连续「工具失败且零业务进展」轮数，≥2）
        max-consecutive-idle-rounds: 3      # 收敛熔断阈值（连续「零事实进展」无工具调用轮数，≥2）
      context:
        window-limit: 32000                 # ⚠️ 已废弃：窗口阈值统一走 cy.orrery.memory.context.*；本节仅在 cy.orrery.memory.enabled=false 时回退生效
        compress-threshold: 0.8             # （同上，废弃）
        hard-compress-threshold: 0.92       # （同上，废弃）
        recent-keep-count: 3                # （同上，废弃）
      pipeline:
        max-transitions: 50                 # 阶段链最大转移次数（转移熔断）
        max-rollbacks: 5                    # 阶段链最大回退次数（回退预算）
      tool:
        exec-pool-size: 8                   # 兼容保留（工具执行已改虚拟线程按任务创建，不生效）
        timeout-millis: 30000               # 单次工具执行超时（强取消）
        arg-fuse-threshold: 2               # 相同参数指纹熔断阈值（≤0 关闭）
        failure-streak-threshold: 3         # 单工具连续失败升级阈值（≤0 关闭）
      llm:
        retry-max-retries: 2                # 瞬时失败最大重试次数（0 关闭）
        retry-backoff-base-millis: 800      # 重试指数退避基准毫秒
        truncation-max-continuations: 2     # 截断自动续接最大轮数（≥1）
      checkpoint:
        enabled: true                       # false 时不装配存储 / 管理器 / 运行门面
        store: memory                       # memory（进程内）| redis（多副本共享）
        durability: ASYNC                   # SYNC | ASYNC | EXIT（落盘时机档位）
        key-prefix: "orrery:agent-runtime:checkpoint:"   # Redis key 前缀（store=redis 时生效）
        ttl-millis: 86400000                # Redis 常规检查点 TTL 毫秒（≤0 不过期）
        suspended-ttl-millis: 604800000     # Redis 挂起检查点 TTL 毫秒（审批/澄清挂起，须比常规长）
      approval:
        gate: memory                        # memory（进程内）| redis（多副本跨实例唤醒 + 决定落 Redis 兜底）
        decision-ttl-millis: 604800000      # Redis 审批决定 TTL 毫秒（gate=redis 时生效，与挂起 TTL 对齐）
      bridge:
        agent-id: my-agent                  # 上报注册中心的 Agent ID（空串表示未指定）
        agent-event-enabled: true           # AgentEvent 线协议桥开关
```

> `tool.*` 与 `llm.*` 两组配置不驱动自动装配，由宿主构造 `GuardedToolInvoker` / LLM 装饰器时读取（引擎实例归宿主组装）。

## 兼容性与运维

**兼容性**：版本随 orrery 生态统一演进（1.0.0）。公开 SPI（`LlmInvoker` / `ToolInvoker` / `LoopDomainSpec` / `StageHandler` / `ApprovalGate` / `ApprovalDecisionStore` / `CheckpointStore` / `ContextGuard` / `MessagePinner` / `AgentEventPublisher`）与 `RuntimeEventType` 事件枚举保持兼容——破坏性变更需评审后随生态主版本进行；配置键只增不改名；废弃项保留一个次版本周期并打 `@Deprecated`（当前已废弃：`cy.orrery.agent-runtime.context.*`，窗口阈值统一走 `cy.orrery.memory.context.*`，本节仅在 `cy.orrery.memory.enabled=false` 时回退生效）。

**告警建议**：

| 信号 | 含义 |
|---|---|
| `orrery.agent.checkpoint.save.failures` 上升 | 异步检查点落盘失败（序列化异常 / 存储不可用） |
| 日志"记忆巩固任务异常"速率上升 | 巩固通道失败（LLM 解析异常等，丢弃当轮） |
| 日志"巩固通道所需层均未开启" / "SESSION 层未开启" | MemoryRunBridge 与 memory 层配置错配（启动期一次性告警） |

## License

MIT License（见本组件目录 [LICENSE](LICENSE)）。

