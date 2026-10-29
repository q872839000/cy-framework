# cy-framework

<div align="center">

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=java&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

cy-framework 是一个面向企业级应用的生产级 Agent 开发框架，聚焦于把“通用企业基础设施能力”和“LLM Agent 运行时能力”统一收敛、标准化、版本治理，并以模块化方式交付给下游业务系统。

它不是一个 demo 级别的 Agent 样例库，而是一个能够直接落地到生产环境的 Java 技术底座：在覆盖 AI Agent 生命周期管理的同时，兼顾缓存、认证、消息、ORM、日志、文件、检索、安全治理等真实业务所需的全栈能力。

## 设计定位

`cy-framework` 解决的核心问题，不是“能不能调用大模型”，而是“如何在企业场景中可靠地构建、部署、运维和演进 Agent 能力”。

它的价值体现在以下几个方面：

- 生产级 Agent 运行时：统一协议、事件、记忆、技能执行、审批门控与执行循环
- 企业级基础设施：缓存、认证、消息、日志、文件、全文检索、系统管理等能力一体化封装
- 标准化版本治理：根 POM 统一锁定依赖版本，降低类冲突和环境漂移问题
- 模块化复用：按能力拆分为多个独立模块，支持按需接入，减少重复造轮子
- 可扩展性：兼容多厂商大模型、支持 OpenAI 兼容协议、可按业务扩展工具与协议适配器

## 这不是简单的 AI SDK，而是生产化 Agent 框架

`cy-framework` 面向真实企业落地设计，具备以下工程化特征：

- 统一的 Agent 协议与事件模型，支撑链路追踪、审计和状态恢复
- 记忆与上下文管理能力，包括短期上下文、场景卡片、语义记忆与画像协同
- 具备工具调用与技能执行框架，支持文件操作、Shell、WebFetch 等内置能力，并具备安全边界
- 支持 HITL（人机协同）审批机制，适合流程型和高风险场景
- 提供企业级安全与治理能力，如认证、权限、审计日志、结构化事件流
- 可直接承接中后台、SaaS、企业助手、知识问答、流程协同、自动化办公等业务场景

## 能力全景

`cy-framework` 以“底座工程 + Agent 运行时”双层结构提供能力：

### 1. 通用基础设施层（cy-commons）

为企业应用提供稳定、通用、低耦合的工程基础能力，包括：

- 通用响应、异常、分页、用户上下文等基础封装
- Web 基础能力：CORS、全局异常处理、Servlet / Reactive 适配
- 分布式缓存与锁：Redisson + Caffeine 二级缓存、缓存注解、Redis 工具类
- 消息中间件封装：LOCAL / REDIS(Stream) / KAFKA
- 数据访问能力：MyBatis-Flex + MySQL、雪花 ID、审计字段自动填充
- 安全认证与鉴权：匿名路径、网关 HMAC 验签、权限与会话能力
- 向量存储与检索：Milvus、Lucene 全文检索
- 文件存储：本地文件、MinIO 对象存储
- 系统管理：用户、角色、菜单、会话等后台能力
- 审计日志与链路追踪：动态月度表、链路信息采集
- 统一 AI 门面：适配 OpenAI 兼容协议，接入多厂商大模型

### 2. Agent 运行时层（cy-orrery-core）

面向“AI Agent 运行时”场景，重点��盖：

- 统一协议与事件模型：AgentDescriptor、AgentCard、Agent Event Protocol
- ReAct / 执行循环引擎：任务规划、工具执行、状态推进
- 技能执行引擎：文件 / Shell / WebFetch 等工具能力与沙箱安全控制
- 记忆系统：L0 上下文窗口、L1 情景卡片、L2 语义记忆、画像决策
- 人在回路（HITL）：审批、确认、执行前控制
- 能力发布与发现：Agent 能力标准化暴露与调用机制

## 模块组成

### cy-commons —— 企业级基础组件库

| 模块 | 说明 |
| --- | --- |
| `cy-common-core` | 基础设施：`R` 响应包装、分页、异常、`AuthUser` / `UserContext` |
| `cy-common-starter-web` | Web 基础能力：CORS、全局异常处理、Servlet / Reactive 支持 |
| `cy-common-cache` | Redisson + Caffeine 二级缓存、分布式锁、缓存注解与 Redis 工具集 |
| `cy-common-mq` | 消息队列封装：LOCAL / REDIS(Stream) / KAFKA |
| `cy-common-orm` | MyBatis-Flex + MySQL，雪花 ID，审计字段自动填充 |
| `cy-common-auth` | 认证授权：匿名路径解析、网关 HMAC 验签、Servlet / Reactive 适配 |
| `cy-common-milvus` | Milvus 向量存储支持 |
| `cy-common-textsearch` | Lucene 全文检索支持 |
| `cy-common-filestore` | 文件存储能力：本地 / MinIO |
| `cy-common-userfill` | 用户信息自动填充切面 |
| `cy-common-starter-system` | 系统管理：用户、角色、菜单等通用能力 |
| `cy-common-starter-conversation` | 会话管理组件 |
| `cy-common-log` | 审计日志与链路追踪，支持动态月度表 |
| `cy-common-ai` | 统一 AI 门面：OpenAI 兼容协议，多厂商模型接入 |

### cy-orrery-core —— Agent 运行时核心

| 模块 | 说明 |
| --- | --- |
| `orrery-protocol` | 共享协议模型：`AgentDescriptor` / `AgentCard` / Agent Event Protocol v1 |
| `orrery-agent-runtime` | Agent 执行框架：ReAct 循环引擎、HITL 审批门、检查点、事件桥、Nacos 身份发布 |
| `orrery-memory` | Agent 记忆子系统：L0 上下文窗口 / L1 情景卡片 / L2 语义记忆 / 画像仲裁 |
| `orrery-skill-runtime` | 技能执行引擎：内置工具（文件 / Shell / WebFetch 等）+ 沙箱路径安全 |

## 生产级特性

### 1. 稳定性与工程治理

- 使用根 `pom.xml` 统一版本管理，锁定 Java / Spring Boot / Spring Cloud / Alibaba 等关键依赖
- 模块解耦、按能力拆分，便于独立升级和灰度演进
- 统一 BOM 与依赖管理，降低下游接入成本和依赖漂移风险

### 2. 可观测性与审计

- 提供审计日志、链路跟踪和事件流，支持 Agent 执行过程可回放
- 可集成日志、监控和追踪平台，帮助排查 AI 执行异常与性能问题
- 统一事件协议让 Agent 行为可分析、可审计、可治理

### 3. 安全与执行控制

- 工具执行具备安全边界，支持沙箱和路径限制
- 支持 Human-in-the-Loop，适合需要审批、确认的高风险流程
- 支持不同场景的权限、认证与会话隔离

### 4. 可扩展的 AI 能力生态

- 兼容 OpenAI 协议的多厂商模型能力
- 可接入不同大模型提供商，切换能力与策略无侵入
- 支持不同业务扩展技能、协议、Provider 与工具适配器

## 典型应用场景

`cy-framework` 非常适合以下场景：

- 企业 AI 助手：知识问答、知识检索、与业务系统协同
- 工作流 Agent：审批、任务编排、智能办公自动化
- 代码与文档协作 Agent：代码阅读、文档检索、研发协同
- 多租户平台：统一认证、缓存、审计、日志、会话管理
- SaaS / 中台系统：统一基础能力，减少重复开发和运维成本
- AI 驱动的业务编排：调用工具、访问数据、执行步骤并反馈结果

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- （可选）Redis / MySQL / Kafka / MinIO / Milvus，依据实际模块启用

### 构建

```bash
mvn clean install
```

### 仅构建特定模块

```bash
# 仅编译当前模块
mvn -pl cy-commons/cy-common-ai -am package

# 仅构建 Agent 运行时模块
mvn -pl cy-orrery-core/orrery-agent-runtime -am package
```

## 下游如何使用

下游仓库建议以 parent 方式接入本仓库，把统一框架层作为上游依赖根工程：

```xml
<parent>
    <groupId>com.cy</groupId>
    <artifactId>cy-framework</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>
```

内部模块版本通过 BOM 方式导入：

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

业务模块按需添加依赖：

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

## 模块说明

### 1. 通用基础设施

`cy-commons` 关注稳定、通用、低耦合的底层能力，覆盖常见的中间件封装与工程实践，包括：

- 统一异常处理与响应体
- 分布式缓存与锁
- MyBatis / 数据库脚手架
- 鉴权与会话能力
- 审计日志与链路追踪
- 文件与全文检索支持
- AI 能力统一封装

这部分能力适合所有 Spring Boot 应用直接复用，无需重复造轮子。

### 2. Agent 运行时

`cy-orrery-core` 面向“AI Agent 运行时”场景，强调：

- 统一的协议与事件模型
- 可组合的智能体执行循环
- 指令与工具执行的安全边界
- 记忆与上下文管理
- 人在回路（HITL）审批门控
- 结构化的 Agent 能力发布与发现机制

这部分能力适合构建：

- 企业 AI 助手
- 代码 / 文档协作 Agent
- 流程审批 Agent
- 工具调用型智能体

## 技术栈与版本策略

本仓库使用统一的根 `pom.xml` 进行版本钉版，重点锁定：

- Java 21
- Spring Boot 3.5.x
- Spring Cloud 2025.x
- Spring Cloud Alibaba 2025.x
- MyBatis-Flex 1.9.x
- Redisson 3.52.x
- Caffeine 3.1.x
- OpenAI 兼容底座与多厂商 AI 接入

该策略的目标是：

- 消除不同模块版本不一致造成的“类加载异常 / 兼容性问题”
- 让下游团队只关心业务能力，不关心底层依赖矩阵
- 便于在多业务线、企业内统一制品版本策略

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

- 统一而不是重复：通用能力集中收敛，不重复发明
- 模块化而不是大而全：按能力拆分，按需接入
- 生产可用而不是 demo 驱动：覆盖缓存、日志、认证、AI、事务等真实工程场景
- 可扩展而不是硬编码：接口抽象、协议标准、Provider 适配
- 工程化落地而不是概念堆砌：从研发、部署、治理、观测等维度理解 Agent

## License

Apache-2.0，详见 [LICENSE](LICENSE)。

---

# cy-framework (English)

`cy-framework` is a production-grade Java framework for building enterprise Agent systems. It combines reusable infrastructure capabilities from `cy-commons` with an Agent runtime from `cy-orrery-core`, providing a unified, version-controlled, modular foundation for real-world LLM applications.

## Highlights

- Production-ready Agent runtime with protocol, memory, tools, events, and HITL approval
- Enterprise infrastructure covering cache, authentication, messaging, ORM, logging, file storage, and search
- OpenAI-compatible AI facade with multi-provider support
- Unified dependency governance and BOM-based downstream integration
- Modular architecture for long-term enterprise adoption

## Quick start

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
