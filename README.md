# cy-framework

<div align="center">

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=java&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

CY 框架层：`cy-commons` 基础组件库 + `cy-orrery-core` Agent 运行时核心。该仓库是全生态的版本钉版单点，采用根 POM 统一管理第三方依赖版本，面向企业级 Java / Spring Boot 应用提供统一的基础设施能力和 Agent 制作运行时能力。

通过该仓库可以按需组合能力：

- 统一的基础组件与通用中间件封装
- Spring Boot Starter 体系与自动装配能力
- 可复用的 Agent 运行时与记忆、技能执行框架
- 面向多厂商大模型与 OpenAI 兼容协议的统一 AI 门面
- 统一的版本治理、模块化依赖与下游集成方式

## 目录

- [概览](#概览)
- [模块组成](#模块组成)
- [快速开始](#快速开始)
- [下游如何使用](#下游如何使用)
- [模块说明](#模块说明)
- [技术栈与版本策略](#技术栈与版本策略)
- [工程结构](#工程结构)
- [License](#license)

## 概览

`cy-framework` 不是单一库，而是一套面向企业级应用的“框架层”仓库，职责是把通用基础设施能力和 LLM Agent 运行时能力统一收敛、标准化、版本管控，并让下游业务工程以最小成本接入。

它的核心价值有三点：

1. 统一版本管理：全仓库使用根 `pom.xml` 统一锁定第三方依赖版本，避免下游依赖漂移。
2. 组件化复用：基础能力拆分为多个独立模块，支持按需引用，降低耦合。
3. Agent 生产级运行时：从协议模型、技能执行、记忆机制到运行时循环，形成完整端到端能力闭环。

如果你正在构建：

- 企业中台、SaaS 平台、后台服务
- 多租户系统、网关与鉴权体系
- AI 助手、Agent 工作流、工具调用系统
- 带审计、日志、缓存、消息、文件等通用能力的应用

那么 `cy-framework` 可作为底座工程来承接底层能力，并减少系统间重复造轮子。

## 模块组成

### cy-commons —— 基础组件库（14 个模块）

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

### cy-orrery-core —— Orrery Agent 运行时核心

| 模块 | 说明 |
| --- | --- |
| `orrery-protocol` | 共享协议模型：`AgentDescriptor` / `AgentCard` / Agent Event Protocol v1 |
| `orrery-agent-runtime` | Agent 执行框架：ReAct 循环引擎、HITL 审批门、检查点、事件桥、Nacos 身份发布 |
| `orrery-memory` | Agent 记忆子系统：L0 上下文窗口 / L1 情景卡片 / L2 语义记忆 / 画像仲裁 |
| `orrery-skill-runtime` | 技能执行引擎：内置工具（文件 / Shell / WebFetch 等）+ 沙箱路径安全 |

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- （可选）Redis / MySQL / Kafka / MinIO / Milvus，依据实际模块启用

### 构建

```bash
mvn clean install
```

### 运行示例

```bash
# 仅编译当前模块
mvn -pl cy-commons/cy-common-ai -am package

# 仅构建 Agent 运行时模块
mvn -pl cy-orrery-core/orrery-agent-runtime -am package
```

## 下游如何使用

下游仓库建议以 parent 方式接入本仓库，将统一框架层作为上游依赖根工程：

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

业务模块继续按需添加依赖：

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

## License

Apache-2.0，详见 [LICENSE](LICENSE)。

---

# cy-framework (English)

`cy-framework` is a Java framework layer combining `cy-commons` (core infrastructure components) and `cy-orrery-core` (Agent runtime). It is designed as a single-version-controlled monorepo that centrally manages dependency versions and exposes reusable capabilities for Spring Boot and enterprise applications.

## What it includes

- Common foundation modules for web, cache, auth, ORM, MQ, file storage, logging, AI facade, and system management
- Agent runtime modules for protocol models, memory, skill execution, and agent orchestration
- Unified dependency management and BOM-based downstream integration
- A reusable platform for enterprise and AI-driven applications

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
