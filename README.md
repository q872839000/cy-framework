# cy-framework

<div align="center">

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=java&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

cy-framework 是一个面向企业级生产环境的 Agent 开发框架，旨在将“通用企业基础设施能力”与“LLM Agent 运行时能力”统一收敛、标准化、版本治理，并以模块化方式提供给下游业务系统。

它不仅支持大模型调用，还覆盖从 Agent 协议、记忆、工具执行、安全治理、审计、日志、缓存、认证、消息、文件、检索等一整套企业级能力，帮助团队快速构建可落地、可扩展、可运维的智能应用。

## Why cy-framework

如果你在构建以下场景，cy-framework 都可以作为有效的底座：

- 企业内部 AI 助手与知识助手
- 工作流式 Agent / 自动化办公 Agent
- 代码协作、文档协作与研发助手
- SaaS / 中台 / 管理后台 AI 化改造
- 多租户系统中的智能能力接入
- 需要安全治理、审计、监控与可控执行的 Agent 系统

cy-framework 不是一个 demo 级的 AI SDK，而是一个能够直接承载生产业务的 Java 技术底座。

## 核心能力

| 能力维度 | 说明 |
| --- | --- |
| Agent 运行时 | 统一协议、事件、执行循环、状态管理、审批控制 |
| 记忆与上下文 | L0/L1/L2 记忆机制，支撑长期学习与任务连续性 |
| 工具执行 | 文件、Shell、WebFetch 等工具能力与安全边界控制 |
| 多模型接入 | OpenAI 兼容协议，多厂商大模型统一门面 |
| 企业基础设施 | 缓存、认证、消息、ORM、日志、文件、检索等通用能力 |
| 工程治理 | 统一 BOM / 版本管理、模块化组装、降低依赖漂移 |
| 可观测性 | 分布式日志、链路追踪、审计事件支持 |
| 可扩展性 | provider、protocol、skill、runtime 皆可按业务扩展 |

## 项目定位

cy-framework 由两层能力组成：

### 1. cy-commons：企业级基础设施层

为 Spring Boot / 企业应用提供通用基础能力，包括：

- 通用响应、异常、分页、用户上下文等基础封装
- Web 基础能力：CORS、全局异常处理、Servlet / Reactive 适配
- 分布式缓存与锁：Redisson + Caffeine 二级缓存、缓存注解、Redis 工具集
- 消息中间件：LOCAL / REDIS(Stream) / KAFKA
- 数据访问：MyBatis-Flex + MySQL、雪花 ID、审计字段自动填充
- 认证与授权：匿名路径处理、网关 HMAC 验签、权限与会话能力
- 文件与检索：本地文件、MinIO、Milvus、Lucene 全文检索
- 系统管理：用户、角色、菜单、会话等后台管理能力
- 审计日志与链路追踪：动态月度表、结构化记录
- 统一 AI 门面：多厂商大模型统一访问入口

### 2. cy-orrery-core：Agent 运行时核心层

面向 LLM Agent 场景，强调：

- 统一协议与事件模型：`AgentDescriptor` / `AgentCard` / Agent Event Protocol
- 执行循环：ReAct / Agent runtime orchestration
- 记忆能力：上下文窗口、场景卡片、语义记忆、画像决策
- 工具执行能力：内置工具与安全控制
- 人在回路（HITL）：审批确认、过程控制、高风险操作保护
- 能力发现与发布：标准化 Agent 能力接口与调用机制

## 模块概览

### cy-commons

| 模块 | 说明 |
| --- | --- |
| `cy-common-core` | 基础设施：响应、分页、异常、`AuthUser` / `UserContext` |
| `cy-common-starter-web` | Web 基础能力：CORS、全局异常处理、Servlet / Reactive 支持 |
| `cy-common-cache` | Redisson + Caffeine 二级缓存、Redis 工具集、分布式锁 |
| `cy-common-mq` | 消息队列封装：LOCAL / REDIS(Stream) / KAFKA |
| `cy-common-orm` | MyBatis-Flex + MySQL、雪花 ID、审计字段自动填充 |
| `cy-common-auth` | 认证授权：匿名路径解析、网关 HMAC 验签 |
| `cy-common-milvus` | Milvus 向量存储支持 |
| `cy-common-textsearch` | Lucene 全文检索支持 |
| `cy-common-filestore` | 文件存储：本地 / MinIO |
| `cy-common-userfill` | 用户信息自动填充切面 |
| `cy-common-starter-system` | 系统管理：用户、角色、菜单等通用能力 |
| `cy-common-starter-conversation` | 会话管理组件 |
| `cy-common-log` | 审计日志与链路追踪 |
| `cy-common-ai` | 统一 AI 门面：OpenAI 兼容协议、多厂商模型接入 |

### cy-orrery-core

| 模块 | 说明 |
| --- | --- |
| `orrery-protocol` | 共享协议模型：角色、事件、Agent 描述 |
| `orrery-agent-runtime` | Agent 执行框架：表达式循环、事件桥、审批门控 |
| `orrery-memory` | 记忆子系统：L0 / L1 / L2 与画像仲裁 |
| `orrery-skill-runtime` | 技能执行引擎：文件 / Shell / WebFetch 等工具支持 |

## 生产级特征

### 稳定性与工程治理

- 根 `pom.xml` 统一锁定依赖版本，减少类冲突和环境漂移
- 模块化拆分，按能力按需接入，降低耦合
- 统一 BOM 与依赖管理，便于企业内统一制品策略

### 安全与可控执行

- 工具执行具备安全边界与沙箱路径控制
- 支持 Human-in-the-Loop（HITL）审批机制
- 统一权限、认证、会话与审计能力，适合高风险业务场景

### 可观测性与审计

- Agent 执行过程具备事件流、日志、链路追踪
- 能够支撑业务监控、排障、审计、合规要求
- 统一协议使 Agent 行为可回放、可分析、可治理

### 扩展能力生态

- 兼容 OpenAI 协议，多模型提供商可切换
- 支持自定义 provider、protocol、skill 与工具扩展
- 适配不同企业业务场景的长期演进需求

## 典型应用场景

- 企业 AI 助手：知识问答、文档检索、办公协同
- 流程 Agent：审批、任务编排、自动化执行
- 代码与文档协作 Agent：研发工作流增强
- 多租户平台：统一认证、日志、缓存、会话与治理能力
- SaaS / 中台：企业级基础设施无缝集成
- AI 驱动的业务编排：调用工具、访问数据、执行步骤并反馈结果

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- （可选）Redis / MySQL / Kafka / MinIO / Milvus，按实际模块启用

### 构建

```bash
mvn clean install
```

### 按模块构建

```bash
# 仅编译当前模块
mvn -pl cy-commons/cy-common-ai -am package

# 仅构建 Agent 运行时模块
mvn -pl cy-orrery-core/orrery-agent-runtime -am package
```

## 下游接入方式

建议下游工程通过 parent 方式接入本仓库：

```xml
<parent>
    <groupId>com.cy</groupId>
    <artifactId>cy-framework</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>
```

模块版本通过 BOM 管理：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.cy.commons</groupId>
            <artifactId>cy-commons-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>
            <groupId>com.cy.orrery</groupId>
            <artifactId>cy-orrery-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

业务模块按需依赖：

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-cache</artifactId>
</dependency>

<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-ai</artifactId>
</dependency>
```

## 工程结构

```text
cy-framework/
├── pom.xml
├── LICENSE
├── README.md
├── cy-commons/
│   ├── pom.xml
│   ├── cy-common-core/
│   ├── cy-common-starter-web/
│   ├── cy-common-cache/
│   ├── cy-common-mq/
│   ├── cy-common-orm/
│   ├── cy-common-auth/
│   ├── cy-common-milvus/
│   ├── cy-common-textsearch/
│   ├── cy-common-filestore/
│   ├── cy-common-userfill/
│   ├── cy-common-starter-system/
│   ├── cy-common-starter-conversation/
│   ├── cy-common-log/
│   └── cy-common-ai/
├── cy-orrery-core/
│   ├── pom.xml
│   ├── orrery-protocol/
│   ├── orrery-skill-runtime/
│   ├── orrery-memory/
│   └── orrery-agent-runtime/
└──
```

## 设计原则

- 统一而不是重复：通用能力集中收敛，不重复造轮子
- 模块化而不是大而全：按能力拆分，按需接入
- 生产可用而不是 demo 驱动：面向真实工程场景与治理要求
- 可扩展而不是硬编码：接口抽象、协议标准、Provider 适配
- 工程化落地而不是概念堆砌：从研发、部署、监控和治理的维度支撑 Agent

## License

Apache-2.0 Licensed. See [LICENSE](LICENSE).

---

# cy-framework

`cy-framework` is a production-grade Java framework for building enterprise Agent systems. It combines reusable infrastructure capabilities from `cy-commons` with an Agent runtime from `cy-orrery-core`, providing a unified, modular foundation for real-world LLM applications.

## Highlights

- Production-ready Agent runtime with protocol, memory, tools, events, and HITL approval
- Enterprise infrastructure covering cache, authentication, messaging, ORM, logging, file storage, and search
- OpenAI-compatible AI facade with multi-provider support
- Unified dependency governance and BOM-based downstream integration
- Modular architecture designed for long-term enterprise adoption

## Quick Start

```bash
mvn clean install
```

This project requires JDK 21 and Maven 3.8+.

## Usage in downstream projects

```xml
<parent>
    <groupId>com.cy</groupId>
    <artifactId>cy-framework</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>
```

Licensed under Apache-2.0. See [LICENSE](LICENSE).
