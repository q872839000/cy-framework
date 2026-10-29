# cy-framework

<div align="center">

[![Java 21](https://img.shields.io/badge/Java-21-ED8B00?logo=java&logoColor=white)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.8%2B-C71A36?logo=apachemaven&logoColor=white)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

</div>

[English](README.md) | 中文

cy-framework 是一个面向企业级生产环境的 Java Agent 开发框架，聚合了 `cy-commons` 的基础设施能力和 `cy-orrery-core` 的 Agent 运行时能力，目标是为真实业务场景提供统一、模块化、可扩展的 AI 能力底座。

它不是一个 demo 级别的 AI 示例工程，而是一个能够落地到生产环境的技术框架：支持多模型接入、工具执行、记忆、审批、审计、日志、缓存、安全治理与企业级基础设施集成。

## 为什么选择 cy-framework

如果你正在构建以下场景，cy-framework 可以作为有效底座：

- 企业内部 AI 助手与知识助手
- 工作流与流程自动化 Agent
- 代码协作、文档协作与研发助手
- SaaS / 中台 / 管理后台的 AI 化改造
- 多租户系统中的统一安全、缓存、日志和会话能力

## 架构设计

cy-framework 分为两层能力：

### 1. cy-commons：企业级基础设施层

该层提供 Java 企业应用所需的通用能力：

- Web 基础能力：CORS、统一异常处理、Servlet / Reactive 适配
- 缓存与分布式协调：Redisson + Caffeine、Redis 工具类、分布式锁
- 消息中间件：LOCAL / REDIS(Stream) / KAFKA
- 数据访问：MyBatis-Flex、MySQL 支持、雪花 ID、审计字段
- 安全认证：鉴权、匿名路径处理、网关 HMAC 校验
- 文件与搜索：Milvus、Lucene、本地 / MinIO 文件存储
- 系统管理：用户、角色、菜单、会话管理组件
- 可观测性：审计日志与链路追踪支持
- AI 门面：兼容 OpenAI 协议的统一大模型访问层

### 2. cy-orrery-core：Agent 运行时核心层

该层聚焦 Agent 执行与编排：

- 统一协议与事件模型
- ReAct / 运行时执行循环
- L0 / L1 / L2 记忆体系
- 工具调用与安全边界控制
- 人在回路（HITL）审批门控
- Agent 能力发布与发现机制

## 模块概览

### cy-commons

| 模块 | 说明 |
| --- | --- |
| `cy-common-core` | 基础设施：响应封装、分页、异常、`AuthUser` / `UserContext` |
| `cy-common-starter-web` | Web 基础能力与常见适配层 |
| `cy-common-cache` | Redisson + Caffeine 二级缓存、Redis 工具、分布式锁 |
| `cy-common-mq` | LOCAL / REDIS(Stream) / KAFKA 消息抽象 |
| `cy-common-orm` | MyBatis-Flex + MySQL、雪花 ID、审计字段支持 |
| `cy-common-auth` | 认证授权基础模块 |
| `cy-common-milvus` | Milvus 集成 |
| `cy-common-textsearch` | Lucene 检索支持 |
| `cy-common-filestore` | 本地 / MinIO 文件存储抽象 |
| `cy-common-userfill` | 用户信息自动填充 |
| `cy-common-starter-system` | 用户、角色、菜单等系统能力 |
| `cy-common-starter-conversation` | 会话管理组件 |
| `cy-common-log` | 审计日志与链路追踪 |
| `cy-common-ai` | 多模型统一 AI 门面 |

### cy-orrery-core

| 模块 | 说明 |
| --- | --- |
| `orrery-protocol` | 共享协议与事件模型 |
| `orrery-agent-runtime` | Agent 执行引擎与运行时编排 |
| `orrery-memory` | 上下文、情景记忆、语义记忆能力 |
| `orrery-skill-runtime` | 工具执行运行时与安全控制 |

## 生产级特征

- 根 `pom.xml` 统一管理依赖版本，降低类冲突和环境漂移
- 模块化设计，方便企业内按能力接入与升级
- 兼容多云、多模型的统一 AI 接口
- 支持高风险操作的人在回路审批
- 提供日志、链路追踪和审计能力以支撑生产运行
- 标准化的协议和运行时契约，支持长期演进

## 快速开始

### 环境要求

- Java 21+
- Maven 3.8+
- 可选依赖：Redis / MySQL / Kafka / MinIO / Milvus（根据启用模块决定）

### 构建

```bash
mvn clean install
```

### 按模块构建

```bash
# 构建 AI 相关公共模块
mvn -pl cy-commons/cy-common-ai -am package

# 构建 Agent 运行时模块
mvn -pl cy-orrery-core/orrery-agent-runtime -am package
```

## 下游接入方式

推荐将本仓库作为上游依赖根工程：

```xml
<parent>
    <groupId>com.cy</groupId>
    <artifactId>cy-framework</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>
```

通过 BOM 统一管理模块版本：

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

## 工程结构

```text
cy-framework/
├── pom.xml
├── LICENSE
├── README.md
├── README.zh-CN.md
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

- 统一而不是重复：将共性能力集中收敛
- 模块化而不是大而全：按能力拆分，按需接入
- 生产可用而不是 demo 驱动：面向真实工程场景与治理要求
- 可扩展而不是硬编码：接口抽象、协议标准、Provider 适配
- 工程化落地而不是概念堆砌：覆盖研发、部署、监控与治理

## License

Apache-2.0，详见 [LICENSE](LICENSE)。
