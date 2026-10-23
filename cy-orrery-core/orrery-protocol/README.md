# orrery-protocol

Orrery 平台的纯模型协议模块：Agent/能力/技能描述符、调用与任务 DTO、Agent 事件协议 v1、
Nacos 资产通道约定常量。**零外部中间件依赖**（不依赖 Nacos/gRPC/Redis），发布方与消费方共用。

## 1. 包结构

| 包 | 内容 |
|---|---|
| `model` | AgentDescriptor（Agent 描述符）、CapabilityDescriptor、SkillDescriptor、InvocationDescriptor、AuthDescriptor、ConstraintDescriptor、ResilienceDescriptor、MemoryDescriptor、RetryPolicy、AgentCard |
| `dto` | AgentCatalogSnapshot（目录快照与路由条目）、InvokeAgentRequest/Response、AgentStreamEvent、TaskEvent、TaskInfo |
| `enums` | AgentType / AgentMode / InvocationMode / InvocationPolicy / SkillKind / SkillVisibility / SkillOwnerType / SkillSource / SkillInstallState / SideEffectLevel / SnapshotChangeType / TaskState / TaskEventType / TransportType / InvokeSource / MemoryOwner / MemoryVisibility / RetentionPolicy |
| `event` | **Agent 事件协议 v1**：AgentEvent 信封 + sealed EventPayload + 事件枚举，全生态 agent 运行时输出的线协议标准；域扩展走 `DomainData.ext` / `RunResult.ext`，不进内核。规范：`docs/agent-event-protocol-v1.md` |
| `constant` | `OrreryConstants`：协议版本 + Nacos 通道约定（见 §2） |
| `converter` | `AgentCardConverter`（AgentDescriptor → A2A 卡片）、`AgentCatalogEntryMapper`（AgentDescriptor → 目录路由条目投影，发布/订阅双端共用） |

## 2. Nacos 通道约定（`OrreryConstants`）

| 约定 | 值 | 用途 |
|---|---|---|
| `AGENT_CATALOG_GROUP` | `AGENT_CATALOG_GROUP` | Agent 富语义目录的 Config group |
| `AGENT_CATALOG_DATA_ID_PREFIX` | `agent-catalog-` | 目录 dataId 前缀（Nacos dataId 不允许 `/`） |
| `AGENT_CATALOG_WATCH_PATTERN` | `agent-catalog-*` | fuzzyWatch 集合订阅模式串（必须带通配符） |
| `agentCatalogDataId(agentId)` | `agent-catalog-{agentId}.json` | 单 Agent 目录 dataId（内容 = 完整 AgentDescriptor JSON） |
| `AGENT_CARD_CATALOG_EXTENSION_URI` | `orrery://catalog` | A2A 卡片 extensions 中富语义副本的 URI |
| `TACTICS_GROUP` | `TACTICS_GROUP` | 战术知识热更通道 group |

## 3. 运行时角色

- **发布方**：orrery-agent-runtime `identity` 包（`AgentIdentityPublisher`）把宿主声明的
  AgentDescriptor 写入目录 dataId + A2A 卡片。
- **订阅方**：orrery-butler-service `CatalogChannelSubscriber` 经 fuzzyWatch（集合增删）+
  addListener（内容秒级推送）消费目录 dataId，经 `AgentCatalogEntryMapper` 投影刷入路由视图。
- **事件**：Agent 运行输出经 `event` 包线协议直发用户端（SSE），不经 Nacos。

## 4. 环境要求

| 依赖 | 版本 |
|---|---|
| JDK | 21+ |
| Jackson / Fastjson2 | 由消费方带入（本模块仅模型，无序列化框架绑定） |

## 5. 引入依赖

```xml
<dependency>
    <groupId>com.cy.orrery</groupId>
    <artifactId>orrery-protocol</artifactId>
</dependency>
```
