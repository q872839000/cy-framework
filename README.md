# cy-framework

CY 框架层：**cy-commons 基础组件库** + **cy-orrery-core Agent 运行时核心**。本仓库是全生态的版本钉版单点（根 POM 统一管理第三方依赖版本），下游服务仓库以 parent POM 方式消费。

只用一个仓即可构建完整的 Agent 开发框架：可复用的 Spring Boot  starter 组件 + LLM Agent 运行时（ReAct 循环引擎、HITL 审批门、检查点、记忆子系统、技能运行时）。

## 模块组成

### cy-commons —— 基础组件库（14 个模块）

| 模块 | 说明 |
|------|------|
| cy-common-core | 基础设施：R 响应包装、Condition 分页、CYException、AuthUser/UserContext |
| cy-common-starter-web | CORS、全局异常处理、Servlet/Reactive 支持 |
| cy-common-cache | Redisson + Caffeine 两级缓存、@Lock4j 分布式锁 |
| cy-common-mq | 消息队列三驱动：LOCAL / REDIS(Stream) / KAFKA |
| cy-common-orm | MyBatis-Flex + MySQL，雪花 ID、审计字段自动填充 |
| cy-common-auth | 认证授权：匿名路径四层解析、网关 HMAC 验签（Servlet + Reactive） |
| cy-common-milvus | Milvus 向量存储 |
| cy-common-textsearch | Lucene 全文检索 |
| cy-common-filestore | 文件存储：本地 / MinIO |
| cy-common-userfill | 用户名自动填充切面 |
| cy-common-starter-system | 系统管理（用户/角色/菜单） |
| cy-common-starter-conversation | 会话组件 |
| cy-common-log | 审计日志与链路追踪（动态月度表） |
| cy-common-ai | LLM 统一门面 AiFacade（OpenAI 兼容 API，多厂商） |

### cy-orrery-core —— Orrery Agent 运行时核心

| 模块 | 说明 |
|------|------|
| orrery-protocol | 共享协议模型：AgentDescriptor / AgentCard / Agent Event Protocol v1（事件协议见 `orrery-protocol/docs/agent-event-protocol-v1.md`） |
| orrery-agent-runtime | Agent 执行框架：ReactLoopEngine 循环引擎、HITL 审批门（memory/Redis）、检查点、事件桥、Nacos 身份发布 |
| orrery-memory | Agent 记忆子系统：L0 上下文窗口 / L1 情景卡片 / L2 语义记忆（向量召回）/ 画像仲裁 |
| orrery-skill-runtime | 技能执行引擎：内置工具（文件/Shell/WebFetch 等）+ 沙箱路径安全 |

## 构建

要求：JDK 21、Maven 3.8+

```bash
mvn clean install
```

## 下游如何使用

下游仓库的根 POM 以 parent 方式挂接本仓（第三方版本零拷贝）：

```xml
<parent>
    <groupId>com.cy</groupId>
    <artifactId>cy-framework</artifactId>
    <version>1.0.0</version>
    <relativePath/>
</parent>
```

内部模块版本经 BOM 导入：

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

## License

Apache-2.0，见 [LICENSE](LICENSE)。

---

# cy-framework (English)

The framework layer: **cy-commons** (14 reusable Spring Boot starter modules) + **cy-orrery-core** (LLM agent runtime: ReAct loop engine, HITL approval gates, checkpoints, memory subsystem, skill runtime with sandboxed builtin tools). The root POM is the single source of truth for third-party dependency versions across the ecosystem; downstream service repositories consume it as a parent POM.

**Requirements:** JDK 21, Maven 3.8+. Build with `mvn clean install`. Downstream projects attach `com.cy:cy-framework` as their parent POM and import `cy-commons-bom` / `cy-orrery-bom` for internal module versions.

Licensed under Apache-2.0. See [LICENSE](LICENSE).
