# orrery-memory 分层记忆重构设计

> 目标：把 orrery-memory 从"两轴模型（5 归属层 × 3 形态）"重构为面向 Agent 的**分层记忆组件**。不兼容旧实现，旧包整体删除重写。
>
> 参考：Claude Code 源码（compact / SessionMemory / extractMemories / autoDream / memdir 五套机制与提示词原文）、OpenViking L0/L1/L2 上下文分层、Hermes L0/L1/L2 分层记忆、Mem0 的提取-决策写入管线。

## 一、现状问题（为什么重构）

| 现状 | 问题 |
|---|---|
| 两轴模型：5 归属层（session/working/user/long-term/agent-private）× 3 形态（FACTS/PROFILE/GRAPH） | 维度爆炸，消费方要先学矩阵才能用；"层"是归属概念，不是 Agent 记忆的温度概念 |
| 画像、事实、图谱、摘要挤在一个 MemoryManager 门面里 | 90+ 类、门面职责过重，agent 侧只想要"注入什么、记住什么"两个动作 |
| 检索是"向量 + MySQL 关键词模糊查询"双通道混合 | 关键词模糊查对语义记忆是噪声源（用户明确要求：只做语义召回） |
| 画像 schema 是组件内置五字段 | 业务维度（军种、指令语义偏好…）无法表达，维度定义权应在消费方 |
| 摘要/提取提示词硬编码在 Java 文本块里 | 业务 Agent 无法按场景覆盖提示词 |
| GRAPH 形态（双时序图谱） | 重、与本次三大诉求无关，本次移除，后续确有需要再独立引入 |
| 能力默认捆绑装配 | 有的 Agent 只需要上下文管理，不需要长期记忆/画像——必须可按需装配 |
| 上下文窗口的上下游契约缺失 | 谁持有消息列表、上游传全量还是增量、过期后怎么办，旧实现没有显式设计 |

## 二、分层模型（L0 / L1 / L2）

层按**温度（离当前对话多近、多快被用到）**划分，不再按归属划分。归属降级为每条记忆的 `scope` 属性（session / user / agent / tenant）。

```
┌─────────────────────────────────────────────────────────────┐
│ L0 活跃上下文（Context）                                      │
│   当前对话的消息窗口本身：滑动窗口 + 折叠 + 滚动摘要             │
│   窗口由组件托管（Redis），上游只追加新消息                     │
├─────────────────────────────────────────────────────────────┤
│ L1 情景记忆（Episodic）                                       │
│   会话/run 结束沉淀的"经历卡片"：本次做了什么、结果如何          │
│   短 TTL（默认 7 天），语义召回，回答"上次类似情况怎么做的"      │
├─────────────────────────────────────────────────────────────┤
│ L2 长期语义记忆（Semantic）                                   │
│   从对话中蒸馏的事实/偏好/经验，跨会话生效，不过期              │
│   向量语义召回（唯一召回方式），写入走 LLM 提取-决策管线         │
└─────────────────────────────────────────────────────────────┘
     用户画像（Profile）—— 独立于三层之外的结构化子系统
     每属主一份条目化档案，维度由消费方定义，整档/按维召回注入
```

### 每条 L2 记忆内部再分三级抽象（借鉴 OpenViking）

| 抽象级 | 内容 | 用途 |
|---|---|---|
| abstract | 一句话索引（≤50 字） | 召回候选清单、注入时的廉价代表 |
| content | 记忆正文（蒸馏后的事实陈述） | 命中后注入的正文 |
| sourceRef | 来源指针（sessionId/turn/原文片段） | 需要原文时按指针回查，不进默认注入 |

召回与注入默认只碰 abstract + content；raw 原文永远按需经工具回查。这是 token 成本控制的关键：**90% 的对话只需要索引级和摘要级信息**。

## 三、一次对话中的完整使用流程

```
 上游（Agent 服务）                记忆组件
 ─────────────────────────────────────────────────────────────
 run 启动
   │ open(sessionId) ──────────▶ 从 Redis 恢复窗口：
   │                             滚动摘要 + 窗口内未驱逐消息
   │ assemble(...) ───────────▶ ① 画像段 ProfileService.render(≤500t)
   │                             ② 相关记忆段 L2+L1 向量召回(≤1000t)
   │                             ※ 能力未装配 → 该段静默缺席
   │ ◀──── 注入段 + 窗口消息 ── 拼进 system/消息列表
   │
 对话进行（上游只追加新消息，永不重传全量历史）
   │ append(本轮 user 消息) ──▶ 进窗口
   │ 每次调 LLM 前 prepareForLlm() ──▶ 窗口守望（§四，可能折叠/摘要）
   │ ◀──── 可直接发送的消息列表
   │ （可选）MemoryTools.recall("...") ──▶ L2/L1 向量召回，自助回填
   │
 run 结束
   │ close() ─────────────────▶ 窗口快照 + 终态摘要写回 Redis（TTL 续期）
   │ （异步，虚拟线程，互不拖垮）
   │                          ① L2 巩固：LLM 提取 → embedding
   │                            → 近邻比对 → LLM 决策 ADD/UPDATE/
   │                            DELETE/NOOP → 落 MySQL + 写 Milvus
   │                          ② L1 情景卡片 → Redis(7d) + Milvus
   │                          ③ 画像更新：★不在默认管线★
   │                            由消费方在自己选定的时机调
   │                            ProfileService.extractAndUpdate(...)
```

下一轮同 session 的 run：`open` 把窗口（摘要 + 最近若干轮原文）读回，连续性由此而来。完整对话记录由业务自己的 conversation 表保存，供前端展示与审计——**那是给人看的权威副本；组件的窗口是给模型看的工作副本**，两份数据用途不同、各存各的。

## 四、L0 上下文管理详解

### 4.1 一句话说清折叠与摘要的关系

**每次准备调用 LLM 之前（包括工具循环中的每一跳，不只是用户消息进来时）估算一次 tokens；没到阈值什么都不做；到了阈值先折叠，折叠压不下去才摘要。**

折叠和摘要不是二选一，是**两级抢救**：

```
prepareForLlm() 的决策流（每次都走，顺序固定）：

  tokens = 估算(当前消息列表)
  │
  ├─ tokens < 0.8 × window-limit
  │     → 什么都不做，原样发送
  │
  └─ tokens ≥ 常规阈值（0.8）
        │
        ▼ 第一级·折叠（不调 LLM，确定性，零延迟零成本）
        把"保留区"以外的 tool 消息正文替换成占位符
        （如 "[tool result, 4231 chars elided]"）
        user/assistant 消息此级一律不碰
        │
        ├─ 折叠后回到水位内 → 发送，结束
        │
        ▼ 第二级·驱逐 + 摘要（调 LLM，有成本有延迟）
        把最旧的若干轮（user/assistant 轮次）整体移出消息列表，
        其文本与既有滚动摘要一起交给 LLM 合并成新摘要，
        摘要作为消息列表的第一条（system 之后）放回窗口
        │
        └─ 循环直到回到水位内

  tokens ≥ 硬阈值（0.92）时跳过渐进步骤：
  保留区直接收缩到最近 1 轮，第一级按此执行，仍超再走第二级
```

- **折叠**：删的是"工具证据"——tool 消息的大体积正文（检索结果、文件内容、命令输出）。这类内容模型用完即弃，删了不影响对话连贯性。不调 LLM，毫秒级。
- **摘要**：压的是"对话本体"——被移出窗口的最旧 user/assistant 轮次。这些内容有价值不能静默丢，所以交给 LLM 压成一段话留在窗口开头。有 LLM 成本，所以只作为折叠压不住时的后备。
- **最近 N 轮（`recent-keep-rounds`，默认 3）是保留区**：永不折叠、永不驱逐，任何水位下都保留原文——正在进行的讨论不能被动。

### 4.2 上下游契约：谁持有消息、传什么

**窗口由组件托管，上游只传增量。**

```java
public interface ContextManager {
    /** run 开始：按 sessionId 打开（或恢复）该会话的上下文窗口 */
    ContextSession open(String sessionId);
}

public interface ContextSession extends AutoCloseable {
    /** 上游只追加本轮新增的消息（user/assistant/tool），不重传全量历史 */
    void append(List<ChatMessage> newMessages);

    /** 每次调 LLM 前调用：内部执行 4.1 的决策流，返回可直接发送的消息列表 */
    List<ChatMessage> prepareForLlm();

    /** run 结束：窗口快照 + 终态摘要写回 Redis（滑动续期 TTL），异步巩固随后触发 */
    @Override void close();
}
```

对上游（agent-runtime / 业务 Agent）的约束：

1. **不要每次重传全量聊天历史**。窗口状态（摘要 + 未驱逐消息）组件自己存 Redis；上游每轮只 append 新消息。重传全量会让"已驱逐/已折叠"的内容复活，窗口管理失效。
2. **业务 conversation 表照存全量**。它是前端展示与审计的权威副本，与组件窗口是用途不同的两份数据：一个给人看（全量、永久），一个给模型看（窗口、TTL）。
3. **同 session 串行**：open 用 version 做 CAS，同一会话并发 run 的第二个 open 失败（506 冲突码），由上游排队或拒绝——一个会话同时只能有一个活跃窗口。

### 4.3 存储与过期语义

Redis 键：`orrery:mem:ctx:{sessionId}`，值：

```json
{
  "version": 17,
  "summary": "……滚动摘要正文……",
  "messages": ["窗口内未驱逐的消息（含折叠占位符状态）"],
  "updatedAt": "2026-09-10T08:00:00Z"
}
```

| 事件 | 行为 |
|---|---|
| 每次 close() | 覆写同键，version+1，TTL 重置为 `summary-ttl-seconds`（默认 24h，滑动续期） |
| TTL 到期（用户超过 24h 没回来） | 键消失。下次 open 得到**空窗口**，按全新会话开始：无摘要、无历史轮次，注入段静默缺席 |
| 到期前的抢救 | **不做**。理由：每次 run 结束时，长期价值已经被两条异步管线各自提取过——事实进了 L2、经历进了 L1。TTL 到期丢的只是"工作现场"（最近几轮原文 + 摘要），而超过 24h 未动的会话，其工作现场本就无续接价值。这是显式契约，不是漏洞 |
| LLM 摘要失败 | 降级为驱逐段原文截断拼接（信息不丢、无压缩），下轮窗口守望会再次尝试压缩 |
| Redis 整体不可用 | 窗口退化为"仅本 run 内存活"（close 时不持久化），组件照常工作，跨 run 连续性缺失并告警 |

### 4.4 数值示例（window-limit=32000）

| 水位 | tokens | 行为 |
|---|---|---|
| < 25600 | 正常 | 不动 |
| ≥ 25600（0.8 常规档） | 折叠 | 保留最近 3 轮，更早的 tool 大消息换占位符 |
| ≥ 29440（0.92 硬档） | 强折叠 | 保留区收缩到最近 1 轮再折叠 |
| 折叠后仍超 | 驱逐+摘要 | 最旧轮次并入滚动摘要，循环直到回到水位内 |

### 4.5 无状态模式（备选）

不愿意把消息窗口放进 Redis 的宿主，可用无状态纯算法模式：每次把全量消息列表传给 `ContextWindowManager.applyWindow(list)`，拿处理后的列表回去（旧 `ContextWindowManager` 的能力平移保留）。**两种模式二选一，默认有状态模式。** 无状态模式下摘要不持久化，跨 run 连续性由宿主自己解决。

### 4.6 配置

```yaml
cy.orrery.memory:
  context:
    mode: stateful               # stateful（默认，Redis 托管窗口）| stateless
    window-limit: 32000          # ≤0 关闭窗口守望
    compress-threshold: 0.8
    hard-compress-threshold: 0.92
    recent-keep-rounds: 3
    summary-ttl-seconds: 86400   # 窗口与摘要的 TTL（滑动续期）
    summary-prompt:              # 可覆盖（§九）
```

## 五、用户画像（ProfileService）——维度由消费方定义

**组件只提供"生成、存储、召回"三个能力；记哪些维度、什么标签、何时触发，全部使用方决定。**

```java
/** 画像维度供给 SPI：消费方实现并注册为 Bean，声明"我要记什么" */
public interface ProfileDimensionProvider {
    /** 维度定义列表。key 稳定不变（进存储与提示词），描述会进提取提示词 */
    List<ProfileDimension> dimensions();
}

public record ProfileDimension(
        String key,            // 如 "branch" / "move_semantics"
        String label,          // 展示名，如 "军种" / "「移动」指令语义"
        String extractionHint, // 进提取提示词的判定指引
        boolean multiValue,    // 多值（标签列表）还是单值
        int maxItems) {}       // 多值时的条目上限
```

军事 Agent 示例：

```java
@Bean
ProfileDimensionProvider militaryProfileDimensions() {
    return () -> List.of(
        new ProfileDimension("branch", "军种", "用户所属或常指挥的军种", false, 1),
        new ProfileDimension("move_semantics", "「移动」指令语义偏好",
                "用户说「移动」时，默认指 A) 把实例初始部署位置改到别处，还是 B) 路径规划机动到某地", false, 1),
        new ProfileDimension("design_habits", "体系设计行为", "用户做想定体系设计时的惯用结构与流程", true, 10)
    );
}
```

运行机制：

- **生成（触发权在消费方）**：组件不定触发时机。消费方在自己选定的时机调用 `profileService.extractAndUpdate(userId, dialogueTurns)`——可以每轮结束异步、可以用户说"记住我"时即时、可以管理端手动。提取提示词骨架内置，**维度段由维度定义动态拼装**（每个维度的 label + extractionHint 成为提示词里的一条提取指令），LLM 只输出有证据的维度。
- **存储**：条目化（复用 R0-R10 的条目化/显式优先/状态机仲裁成果，这套是对的保留）：一条画像 = 维度 + 值 + 置信度 + 来源 + 状态。同维度新值与旧值冲突走仲裁（显式 > 推断、新证据优先）。MySQL 单表 `orrery_memory_profile_item`。
- **召回**两种：
  1. **整档注入**：`render(userId, tokenBudget)` 按维度顺序渲染进 system 提示（默认方式，画像通常很小）；
  2. **按维查询**：`getItems(userId, dimensionKey)` 供 Agent 在特定决策点主动查。
- **画像即纠偏（「移动」例子落地）**：scenario-agent 在意图分类阶段把 `move_semantics` 维度的值拼进分类提示词，"移动"的歧义按该用户的历史习惯消解——用户 A 的"移动"路由到初始部署调整，用户 B 的"移动"路由到机动规划。

## 六、L2 长期语义记忆（SemanticMemoryService）

**召回只有一条路：向量语义召回。不做 MySQL 模糊查询，不做关键词兜底。**

```
写入（巩固管线，run 结束异步触发）：
  对话段 → LLM 提取候选事实 → embedding → 近邻比对（向量 topN）
        → LLM 决策 ADD / UPDATE / DELETE / NOOP → 落 MySQL + 写 Milvus
读取：
  query → embedding → Milvus topN → 按 id 回 MySQL 取本体
        → 公式重排（相似度/重要性/新鲜度/使用频率）→ topK
```

- **强依赖声明**：L2 开启时 embedding 与向量索引**必需**，缺一个启动拒绝。没有"降级关键词路"这种假降级。L2 不装配则组件照常工作，只是没有长期记忆。
- **scope 属性**：每条记忆带 `scope = {session|user|agent|tenant} + ownerId`，召回时按 scope 过滤（Milvus expression）。归属只是过滤条件，不再是"层"。
- **kind 属性**：fact / preference / experience（程序性经验）三值收敛。
- **热路径直写保留**：`remember()` 同步直写（Agent 工具"记住这个"），巩固通道有热路径优先窗口防复活。
- **重排公式保留**现有四维权可配（相似度/重要性/新鲜度/使用频率），这套设计是对的。

## 七、L1 情景记忆（EpisodeService）

- run 结束时，把本次会话的滚动摘要 + 关键结果沉淀/滚动更新为一张**情景卡片**（EpisodeRecord：标题、摘要、结果、涉及实体标签、时间、scope）。同 session 一张卡滚动更新。
- 存 Redis（TTL 默认 7 天可配）+ 摘要文本 embedding 进 Milvus（打 episode 标记）。
- 召回与 L2 同一条向量路，只是 filter 不同。回答"上次做类似想定时怎么部署的"这类问题。
- 不装配则组件只有 L0 + L2 + 画像。

## 八、可选装配（能力不是强制的）

每个子系统独立开关、独立依赖、独立装配；未装配的能力其 Bean 不存在，`MemoryContextAssembler` 跳过对应注入段（静默缺席，不报错）。

| 装配档 | 开关 | 需要的中间件 | 适用 Agent |
|---|---|---|---|
| 纯上下文 | 只引入 jar（L0 默认在） | 无；要跨 run 恢复窗口则需 Redis | 无状态工具型 Agent |
| + 画像 | `profile.enabled: true` | MySQL | 需要"越用越懂你"的 Agent |
| + 长期记忆 | `semantic.enabled: true` | MySQL + Milvus + embedding 服务 | 需要跨会话积累经验 |
| + 情景记忆 | `episodic.enabled: true` | Redis + Milvus + embedding | 需要"上次类似情况"回放 |
| 满配 | 全开 | 全部 | butler 这类平台级编排 |

装配契约：

```java
MemoryCapabilities caps = memoryCapabilities();  // 启动后自检：哪些能力在线、各走什么存储
// 消费方据此决定自己的功能面，例如 scenario-agent 检测到 profile 不在线就跳过画像注入
```

```yaml
cy.orrery.memory:
  enabled: true
  profile:  { enabled: false }     # 这个 Agent 不要画像
  semantic: { enabled: true }      # 要长期记忆（缺 Milvus/embedding → 启动拒绝，把误配暴露在启动期）
  episodic: { enabled: false }
```

## 九、提示词资产（内置默认全文，全部可覆盖）

### 9.1 为什么默认用英文提示词（采纳 Claude Code 的做法）

默认提示词**直接改编 Claude Code 的英文原文，不另起中文稿**，理由：

1. **经过大规模生产验证**。Claude Code 的 compact / extract / consolidation 提示词在海量真实会话上迭代过，其结构（必保清单、负面清单、冲突处理、输出契约）直接继承，不重新发明。
2. **英文提示词的指令遵循更稳**。主流模型（含 Qwen 等国产模型）的训练语料中英文指令数据占比与质量更高，对"必须/禁止/输出契约"类指令的遵循度英文普遍优于中文。
3. **内容语言与指令语言解耦**。提示词用英文下指令，同时显式要求"用对话的主导语言写输出"（见各提示词的 language 条款），中文对话产出的摘要仍是中文。
4. **可覆盖是兜底**。业务用中文微调模型、或领域有特定要求时，经 yml/Bean 整体替换——三级覆盖机制不变：Spring Bean > yml 配置 > 内置默认。

### 9.2 `prompts/context-summary.md`（L0 滚动摘要合并）

改编自 Claude Code compact 提示词：保留其"分节 + 必保清单 + 冲突处理 + 纯文本输出"骨架，改为"既有摘要 + 驱逐段"的滚动合并语义，砍掉文件/代码段枚举等代码仓特定要求。

```text
You are a conversation summarizer. Merge the EXISTING SUMMARY and the EVICTED
SEGMENT into one new rolling summary. It will be the ONLY record of the earlier
portion of this conversation, so it must preserve everything needed to continue
the work without losing context.

ALWAYS PRESERVE:
- The user's explicit requests and intents (each one, in detail)
- Decisions made, agreements reached, and user preferences
- Pending tasks and in-progress work, with precise current state
- Key facts, figures, names, and conclusions
- Errors that were corrected and the corrected approach, especially where the
  user said to do something differently (so the mistake is never repeated)

ALWAYS DISCARD:
- Greetings, pleasantries, empty confirmations
- Concluded temporary details that carry no future value

CONFLICT HANDLING: when old and new content conflict, prefer the newer; note the
overturned conclusion in one line (format: "Previously ..., now ...").

OUTPUT: the summary body only — no commentary, no preamble, no explanation.
Organize under these sections, omitting any that are empty:
  Requests & Intent / Decisions & Agreements / Pending & In-Progress /
  Key Facts / Lessons

LANGUAGE: write the summary in the dominant language of the conversation.
```

### 9.3 `prompts/fact-extract.md`（L2 事实提取）

改编自 Claude Code extractMemories 提示词。核心继承两条：①"什么不值得记"负面清单；②每条候选必须带证据指针（evidence）。

```text
You are a memory extractor. Read the CONVERSATION SEGMENT and extract only what
is worth remembering long-term. Output a JSON array.

EXTRACT ONLY THESE THREE KINDS:
- fact:       objective facts about the user, business objects, or environment
- preference: preferences and habits of the user or the agent
- experience: practices proven effective (or ineffective) for a class of tasks

DO NOT EXTRACT:
- Information directly queryable from the system's current state
  (memory is not a replica of the database)
- Ephemeral state meaningful only within this conversation
  (intermediate results, one-off instructions)
- Greetings, pleasantries, content-free confirmations
- Unverified speculation — the model's own guesses are not facts

EACH ITEM:
{"kind": "fact | preference | experience",
 "abstract": "one-line index, <=50 chars, fully self-contained, no references
              like 'as above'",
 "content": "the complete, self-contained statement",
 "importance": 1-10,
 "evidence": "the verbatim basis from the conversation"}

RULES:
- Output [] when nothing qualifies.
- When in doubt, leave it out — recall quality depends on precision, not volume.
- Write "abstract"/"content" in the dominant language of the conversation.
```

### 9.4 `prompts/write-decision.md`（L2 写入决策）

Mem0 式四操作决策。

```text
You are a memory decision maker. For each CANDIDATE memory, decide one operation
against the NEAR-NEIGHBOR memories provided.

OPERATIONS:
- ADD:    the candidate duplicates nothing               → create it
- UPDATE: the candidate concerns the same subject as a neighbor but carries
          new information                                → update that neighbor
- DELETE: the candidate explicitly refutes a neighbor    → invalidate it
- NOOP:   the candidate is fully covered by a neighbor   → skip

OUTPUT a JSON array:
{"op": "ADD | UPDATE | DELETE | NOOP",
 "targetId": "required for UPDATE/DELETE — the neighbor's id",
 "content": "required for UPDATE — the merged, self-contained full text;
             never a reference to the old text"}

RULES:
- When unsure, choose NOOP. A duplicate memory is more harmful than a missed one.
- Write "content" in the dominant language of the conversation.
```

### 9.5 `prompts/profile-extract.md`（画像提取骨架）

骨架可覆盖；**`{{dimensions}}` 段永远由 ProfileDimensionProvider 动态拼装、不可被覆盖**——这是维度定义权下放的技术实现点。

```text
You are a user-profile extractor. From the CONVERSATION SEGMENT and the CURRENT
PROFILE, extract profile entries. Output a JSON array.

PROFILE DIMENSIONS — extract ONLY these; record nothing else:
{{dimensions}}
(Assembled at runtime from the consumer's ProfileDimensionProvider, e.g.:
 - branch (军种): the service branch the user belongs to or usually commands. single-valued.
 - move_semantics (「移动」指令语义偏好): when the user says "移动", do they mean
   A) relocating an instance's initial deployment position, or
   B) planning a route and maneuvering to a location. single-valued.
 - design_habits (体系设计行为): the user's habitual structures and workflows in
   scenario-system design. multi-valued, <=10 items.)

RULES:
1. Record only what has explicit evidence in the conversation; omit dimensions
   without evidence.
2. Explicit user statements ("I am...", "remember that I...") outrank behavioral
   inference; inferred entries must set inferred: true.
3. On conflict with the CURRENT PROFILE, still output the new value —
   arbitration is the system's job; do not explain trade-offs in the output.
4. When uncertain, do not extract — a wrong profile is more harmful than a
   missing one.

EACH ENTRY:
{"dimension": "<dimension key>", "value": "...", "inferred": false,
 "evidence": "the verbatim basis"}
```

### 9.6 `prompts/episode-settle.md`（L1 情景卡片）

改编自 Claude Code SessionMemory 模板（当前状态/任务规格/关键结果/教训四节）。

```text
You are a session recorder. From THIS SESSION's content, produce one episode
card for future "what happened last time in a similar situation" retrieval.
Output JSON:

{
  "title":    "<=20 chars, information-dense, no filler",
  "summary":  "what was done, how it was done, and how it ended, <=300 chars",
  "outcome":  "success | failure | aborted — plus a one-line reason",
  "entities": ["key objects/entities involved"],
  "lessons":  ["practices proven effective or ineffective; empty array if none"]
}

RULES:
- The summary is written for someone who has never seen this session:
  no references like "as mentioned above".
- Write field values in the dominant language of the session.
```

## 十、为什么用向量而不是 Claude Code 的 MEMORY.md 文件方案

Claude Code 的长期记忆是**纯文件方案**：记忆是一个个 markdown 文件，`MEMORY.md` 是人工可读的索引（每条一行），召回靠"索引常驻 + LLM 选择器按文件名/描述挑选 + Read 原文"。我们不照搬，但认真对比过：

| 维度 | Claude Code 文件方案 | 本方案的向量方案 |
|---|---|---|
| 运行环境 | 单用户 CLI，本地文件系统是天然载体 | 多租户服务端平台，N 用户 × M Agent 并发 |
| 记忆规模 | 数百条以内（人工可维护索引） | 单 scope 配额 1 万条，平台级总量无上限 |
| 召回发起方 | Agent 自己用工具读文件（多轮交互） | run 启动时系统自动注入（一次到位，无多轮余地） |
| 召回方式 | 文件名+描述的关键词匹配（LLM 选择器） | 语义相似度（"移动一下位置"能命中"调整初始部署坐标"） |
| 并发写入 | 单会话独占，无并发问题 | 多会话并发写同用户记忆，需乐观锁/仲裁 |
| 治理 | 文件即全部，删除即合规 | 审计链/被遗忘权/配额/墓碑，需结构化存储支撑 |
| 基础设施 | 零依赖 | 需要 embedding 服务 + Milvus |
| 可解释性 | 强（人直接读文件） | 弱（需管理端/API 代偿） |

**结论：向量召回是服务端平台的正确选择，文件索引的思想要留下。** 具体：

1. **召回质量是决定性因素**。文件方案的关键词匹配（文件名/描述）在中文业务表述下同义表述命中率低——"移动"的两种语义靠关键词分不开，靠语义向量分得开。这正是用户画像要解决的那类问题。
2. **平台场景没有"Agent 自己翻文件"的余地**。Claude Code 可以让 Agent 花几轮工具调用去读记忆文件；我们的 run 启动注入是一次性装配，召回必须机器自动完成。
3. **MEMORY.md 的两个思想被吸收进本方案**：
   - **索引/正文分级** → L2 记忆内部的 abstract（一句话索引）/ content（正文）/ sourceRef（原文指针）三级抽象，注入默认只带 abstract+content，等价于"MEMORY.md 常驻 + 文件按需读"；
   - **人工可读可管** → `MemoryAdminService` 提供等效能力（查询/更正/作废/擦除），画像整档渲染出来就是一份"MEMORY.md 式"的常驻文档。
4. **文件方案的隐性成本我们付不起**：它把去重、更新、矛盾仲裁全推给了模型自觉（提示词里写"先检查有没有重复"），服务端必须确定性兜底（近邻比对 + 决策管线 + 仲裁状态机）。

一句话：**画像 ≈ MEMORY.md（小、结构化、整档常驻注入），L2 ≈ 记忆文件库（大、按需召回），召回方式从关键词换成语义向量。**

## 十一、存储方案（每种记忆一个去处，不再有路由矩阵）

| 数据 | 存储 | 说明 |
|---|---|---|
| L0 窗口（摘要 + 未驱逐消息） | Redis（TTL 默认 24h，滑动续期） | stateful 模式；组件托管的工作副本 |
| L1 情景卡片 | Redis（TTL 默认 7d）+ Milvus（摘要向量） | 召回走向量路 |
| L2 记忆本体 + 审计链 | MySQL（`orrery_memory_item` / `orrery_memory_revision`） | 只按 id 取，绝不模糊查 |
| L2 向量 | Milvus（semantic.enabled 时必需） | 唯一召回通道 |
| 画像条目 | MySQL（`orrery_memory_profile_item`） | 整档小数据，不需要向量 |
| 完整对话记录 | 业务自己的 conversation 表（组件不管） | 给人看的权威副本：前端展示/审计 |

治理保留现有的生产级能力：审计链（一切变更留痕）、乐观锁、作废不删除（墓碑）、配额淘汰、敏感拒写（MemorySanitizer SPI）、被遗忘权跨存储联动擦除、Micrometer 指标。这些与分层模型正交，整体平移。

## 十二、包结构（旧包全删）

```
com.cy.orrery.memory
├── api/            # MemoryItem(abstract/content/sourceRef/scope/kind)、MemoryScope、
│                   # MemoryQuery、Provenance、MemoryRevision、MemorySanitizer、
│                   # ProfileDimension、ProfileItem、各 SPI
├── context/        # L0: ContextManager(open)、ContextSession(append/prepareForLlm/close)、
│                   # ContextWindowManager(无状态备选)、MessageAdapter、TokenEstimator、
│                   # ContextCompactor、RollingSummarizer、RedisContextWindowStore
├── profile/        # ProfileService、ProfileArbiter、ProfileStore(+jdbc impl)
├── episodic/       # L1: EpisodeService、EpisodeRecord、EpisodeStore(+redis impl)
├── semantic/       # L2: SemanticMemoryService、MemoryExtractor、MemoryDecider、
│                   # MemoryStore(+jdbc)、VectorIndex(+milvus)
├── embedding/      # EmbeddingPort（cy-common-ai 桥，L1/L2 必需）
├── prompt/         # PromptResolver（三级覆盖解析）、默认提示词资源加载
├── assemble/       # MemoryContextAssembler（注入预算装配，缺席段自动跳过）
├── admin/          # MemoryAdminService、ForgettingEngine
└── autoconfigure/  # MemoryAutoConfiguration（按能力条件装配）、MemoryProperties、清扫调度
```

**删除清单**：两轴模型全部（MemoryLayer/LayerGate/LayerSettings）、GRAPH 形态全部（GraphStore/GraphService/JdbcGraphStore/时序图谱/矛盾检测）、文档式画像（MemoryProfile 被条目化画像取代，LegacyProfileChannel 删除）、存储路由矩阵（每层独立 store 配置取消，按 §十一 固定归属）、关键词模糊召回通道。

## 十三、配置参考（重构后）

```yaml
cy.orrery.memory:
  enabled: true
  node-id: -1                       # 雪花节点
  context:                          # L0（永远可用；stateful 模式需 Redis）
    mode: stateful
    window-limit: 32000
    compress-threshold: 0.8
    hard-compress-threshold: 0.92
    recent-keep-rounds: 3
    summary-ttl-seconds: 86400
    summary-prompt:                 # 可覆盖（§九）
  episodic:                         # L1（可选装配）
    enabled: false
    ttl-seconds: 604800
    settle-prompt:
  semantic:                         # L2（可选装配；开启即要求 embedding + milvus，缺则启动拒绝）
    enabled: false
    quota-per-scope: 10000
    dedup-threshold: 0.92
    hot-path-priority-window-seconds: 600
    extract-prompt:
    decision-prompt:
  retrieval:                        # L1/L2 共用召回
    top-k: 8
    weights: { similarity: 0.45, importance: 0.2, recency: 0.2, usage: 0.1 }
    recency-half-life-days: 30
  profile:                          # 画像（可选装配）
    enabled: false
    extract-prompt:                 # 骨架可覆盖；维度段永远由 Provider 动态生成
  injection:                        # 注入预算
    token-budget: 2000
    profile-budget: 500
    recalled-budget: 1000
    session-summary-budget: 500
  security:
    sanitizer-enabled: true
  purge:
    enabled: true
    interval-seconds: 3600
    tombstone-retention-seconds: 604800
```

## 十四、实施分期

| 期 | 内容 | 验收 |
|---|---|---|
| M1 | 新骨架 + api 模型 + L0 上下文管理（ContextSession 有状态窗口/折叠/驱逐+滚动摘要/Redis 存储与 TTL 契约/提示词三级覆盖/无状态备选模式） | 决策流单测（两级阈值、保留区、降级）；TTL 过期与并发 open 单测 |
| M2 | L2 语义记忆：提取-决策管线 + Milvus 召回（唯一通道）+ 热路径 + 重排 + 治理平移（审计/乐观锁/配额/擦除/敏感闸口） | 提取/决策/召回/擦除单测；缺 embedding 启动拒绝测试 |
| M3 | 用户画像：ProfileDimensionProvider SPI + 条目化存储 + 仲裁 + 渲染/按维查询 + 维度段动态拼装提取提示词 | 军事维度示例端到端测试（定义→提取→仲裁→注入） |
| M4 | L1 情景卡片 + MemoryContextAssembler + MemoryTools + 后台巩固编排 + 指标 + 可选装配条件化 | 巩固管线端到端；五档装配组合启动测试 |
| M5 | 接入改造：agent-runtime harness 接 ContextSession；butler MemoryRunBridge 重写；scenario-agent 接画像（move_semantics 类维度示范）；MemoryController 适配 | butler 冒烟全通；scenario 意图分类消歧实测 |

每期交付即可用：M1 后组件就是纯上下文管理库，M3 后画像可独立用，不强依赖 L2。

## 十五、风险与决策点

1. **纯向量召回的冷启动**：无 embedding 服务时 L2/L1 不可用（用户明确要的取舍）。部署文档须写明硬前置。
2. **GRAPH 形态移除**：实体关系多跳推理能力随之移除。若 butler/scenario 后续需要，作为独立子系统再立项，不混回本组件。
3. **画像触发权下放后的空画像**：消费方不调 extractAndUpdate 画像就永远空——接入手册要给推荐触发点（每 run 结束异步、或显式指令即时）。
4. **窗口托管在 Redis 的数据职责**：窗口是派生工作副本，丢了不丢长期价值（L2/L1 已提取）；业务的 conversation 表仍是全量权威副本。接入方必须理解这两份数据的分工。
5. **提示词英文默认稿 vs 中文本地模型**：默认英文（指令遵循更稳 + Claude Code 验证血统），输出语言由提示词条款锁定为对话主导语言；业务用中文微调模型时可整体覆盖为中文稿。
6. **错误码段**：继续占用 506xx，文案全部进 `error-messages-orrery-memory.properties`（CYException 单点规范不变）。
