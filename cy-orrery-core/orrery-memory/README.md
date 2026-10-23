# orrery-memory

Orrery 平台级记忆组件：面向 Agent 的分层记忆（L0 上下文 / L1 情景 / L2 长期语义）+ 维度下放的用户画像，能力按需装配、提示词可覆盖。

## 一、能力总览（非研发可读）

| 能力 | 大白话说明 |
|---|---|
| L0 上下文管理 | 对话窗口的滑动窗口 + 折叠 + 滚动摘要：超阈值先折叠工具大消息（不调 LLM），压不住才把最旧轮次并入摘要；窗口托管在 Redis，跨 run 恢复 |
| 用户画像 | 每个用户一份条目化档案；**记哪些维度完全由使用方定义**（如军事 Agent 定义"军种"、"「移动」指令语义偏好"），组件只管生成、存储、召回 |
| L2 长期语义记忆 | 从对话蒸馏的事实/偏好/经验，跨会话生效；**召回只有向量语义一条路**（无关键词模糊查询） |
| L1 情景记忆 | 会话结束沉淀"经历卡片"（做了什么、结果如何），短 TTL，回答"上次类似情况怎么做的" |
| 治理与合规 | 变更全审计、作废不删除、配额淘汰、敏感拒写、被遗忘权跨存储擦除 |

## 二、分层模型

层按温度划分（离当前对话多近）；归属降级为每条记忆的 scope 属性（session/user/agent/tenant）。

```
L0 活跃上下文：消息窗口本身（组件托管，Redis）
L1 情景记忆  ：会话经历卡片（Redis 7d + 向量索引）
L2 长期记忆  ：蒸馏事实（MySQL 本体+审计，Milvus 向量唯一召回）
用户画像     ：独立子系统（MySQL 条目化，维度消费方定义）
```

每条 L2 记忆内部三级抽象：abstract（一句话索引）/ content（正文）/ sourceRef（原文指针，按需回查）。

## 三、一次对话中的使用流程

```
run 启动   → MemoryContextAssembler 装配注入（画像段+相关记忆段+摘要段，缺席段跳过）
run 中     → 每次调 LLM 前 ContextSession.prepareForLlm()（窗口守望：折叠/驱逐+摘要）
run 中     → Agent 可经 MemoryTools.recall 自助回填
run 结束   → close() 写回窗口；异步巩固：L2 提取-决策 + L1 情景卡片
             （画像更新不在默认管线：消费方自选时机调 ProfileService.extractAndUpdate）
```

## 四、可选装配（能力不强制）

| 装配档 | 开关 | 中间件 |
|---|---|---|
| 纯上下文 | 引入 jar 即可 | 无；跨 run 恢复窗口需 Redis |
| + 画像 | `cy.orrery.memory.profile.enabled: true` | MySQL |
| + 长期记忆 | `cy.orrery.memory.semantic.enabled: true` | MySQL + Milvus + embedding（缺则**启动拒绝**） |
| + 情景记忆 | `cy.orrery.memory.episodic.enabled: true` | Redis + Milvus + embedding |

## 五、快速开始

```xml
<dependency>
    <groupId>com.cy.orrery</groupId>
    <artifactId>orrery-memory</artifactId>
</dependency>
```

```yaml
cy.orrery.memory:
  vector-dimension: 1024        # 由 embedding 模型决定
  profile:  { enabled: true }
  semantic: { enabled: true }
  episodic: { enabled: true }
```

```java
// 画像维度（消费方声明"记什么"）
@Bean
ProfileDimensionProvider militaryDimensions() {
    return () -> List.of(
        new ProfileDimension("move_semantics", "「移动」指令语义偏好",
                "用户说「移动」时指 A) 初始部署调整 还是 B) 机动规划", false, 1));
}

// L2 直写与召回
@Autowired MemoryTools memoryTools;
String id = memoryTools.remember(MemoryScope.ofUser(null, "u123"),
        "用户偏好把新实例先部署到集结地域", "preference", 7, "s1#r1");
List<String> hits = memoryTools.recall("部署习惯", null, 5);

// 画像提取（消费方自选触发时机）
@Autowired ProfileService profileService;
profileService.extractAndUpdate(ProfileItem.OwnerType.USER, null, "u123", transcript);
```

## 六、提示词（全部可覆盖）

内置默认稿为英文（改编自 Claude Code 生产提示词，指令遵循更稳），输出语言由提示词条款锁定为对话主导语言。三级覆盖：

```yaml
cy.orrery.memory:
  context: { summary-prompt: classpath:prompts/my-summary.md }   # Bean 覆盖 > 此配置 > 内置
```

五份默认稿：`prompts/context-summary.md`（滚动摘要）/ `fact-extract.md`（事实提取）/ `write-decision.md`（写入决策）/ `profile-extract.md`（画像提取骨架，**维度段永远由 Provider 动态拼装**）/ `episode-settle.md`（情景卡片）。

## 七、配置参考

```yaml
cy.orrery.memory:
  enabled: true
  node-id: -1                       # 雪花节点（-1 = 按主机名派生；多实例建议显式配置）
  vector-dimension: 0               # embedding 维度（L2/L1 必需）
  jdbc-auto-init: false             # 开发代建表；生产用 resources/orrery-memory/schema.sql
  context:
    mode: stateful                  # stateful（Redis 托管窗口）| stateless（纯算法）
    window-limit: 32000
    compress-threshold: 0.8         # 常规档：折叠保留区外 tool 大消息
    hard-compress-threshold: 0.92   # 硬档：保留区收缩到 1 轮
    recent-keep-rounds: 3           # 保留区：永不折叠、永不驱逐
    summary-ttl-seconds: 86400      # 窗口 TTL（滑动续期；过期按新会话开始，不做抢救）
  profile:  { enabled: false }
  semantic:
    enabled: false
    quota-per-scope: 10000
    dedup-threshold: 0.92
    hot-path-priority-window-seconds: 600
  episodic: { enabled: false, ttl-seconds: 604800 }
  retrieval:
    top-k: 8
    weights 见 MemoryProperties
  injection: { token-budget: 2000, profile-budget: 500, recalled-budget: 1000, session-summary-budget: 500 }
  security: { sanitizer-enabled: true }
  purge: { enabled: true, interval-seconds: 3600, tombstone-retention-seconds: 604800 }
```

**行为契约**：

- 折叠只碰 tool 消息，摘要只合并被驱逐的 user/assistant 轮次（摘要原料零污染）
- 窗口 TTL 过期 = 工作现场失效，下轮按新会话开始；长期价值已在每个 run 结束被 L2/L1 提取
- 同 session 同时只允许一个活跃窗口（并发 open 报 50622）
- L2 召回仅向量语义通道；embedding/Milvus 缺失在启动期拒绝，运行期不降级
- 业务 conversation 表是全量权威副本（给人看）；组件窗口是工作副本（给模型看）

## 八、核心服务一览

| Bean | 职责 | 装配条件 |
|---|---|---|
| `ContextManager` / `ContextSession` | L0 窗口（open/append/prepareForLlm/close） | 永远在 |
| `ContextWindowManager<T>` | 无状态纯算法模式（宿主自持消息列表） | 永远在 |
| `ProfileService` | 画像：extractAndUpdate / render / getItems / edit / erase | profile.enabled |
| `SemanticMemoryService` | L2：remember / recall / correct / supersede / invalidate / erase / history | semantic.enabled |
| `EpisodeService` | L1：settle / recall / eraseBySession | episodic.enabled |
| `ConsolidationService` | run 结束巩固管线（提取→近邻比对→决策→执行） | semantic.enabled |
| `MemoryContextAssembler` | run 启动注入装配（预算内定界包裹） | 永远在 |
| `MemoryTools` | Agent 四工具门面 | semantic.enabled |
| `MemoryAdminService` / `ForgettingEngine` | 管理与被遗忘权擦除 | 随子系统 |
| `MemoryCapabilities` | 能力自检视图 | 永远在 |

## 九、存储与表结构

| 数据 | 存储 |
|---|---|
| L0 窗口（摘要+未驱逐消息） | Redis `orrery:mem:ctx:{sessionId}` |
| L1 情景卡片 | Redis `orrery:mem:episode:{sessionId}` + Milvus（kind=EPISODE） |
| L2 本体 + 审计链 | MySQL `orrery_memory_item` / `orrery_memory_revision`（只按 ID 取，绝不模糊查） |
| L2/L1 向量 | Milvus `orrery_memory_l2_{dim}`（标量过滤在引擎表达式内生效） |
| 画像条目 | MySQL `orrery_memory_profile_item` |

错误码 506xx 段，单点定义于 `error-messages-orrery-memory.properties`。

## 十、设计文档

完整设计（分层模型论证、折叠/摘要决策流、上下游契约、提示词全文、与 Claude Code MEMORY.md 方案对比）：[docs/memory-layered-refactor-design.md](docs/memory-layered-refactor-design.md)

## 许可

MIT License（见 [LICENSE](LICENSE)）。
