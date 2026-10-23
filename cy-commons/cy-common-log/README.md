# cy-common-log

统一日志组件，为 Spring Boot 应用提供**零侵入 API 日志**、**侵入式业务日志**、**方法级调用链路日志 (ACCESS)**，以及受治理的异步 MQ 发布、自动分表持久化、定时清理、采样、指标采集等生产级能力。

> **⚠️ 重要提示**：请仔细阅读本文档的"常见问题"和"最佳实践"章节，避免常见配置错误。

---

## 1. 组件能力总览

| 能力 | 说明 |
|------|------|
| **零侵入 API 日志** | 自动拦截所有 `@RestController` / `@Controller` 方法，记录请求 URI、参数、耗时、用户信息，无需添加任何注解（**Servlet 环境专属**，WebFlux / gRPC 环境不自动激活） |
| **侵入式业务日志** | 通过 `@SysLog` 注解或 `LogService` 编程式 API 手动记录业务操作日志，支持 SpEL 表达式、数据变更前后对比、自动生成变更摘要 |
| **调用链路日志 (ACCESS)** | 通过 `@AccessLog` 注解自动维护方法级调用栈（A → B → C），单次 trace 由 `AccessTraceState` 统一管理，记录每层方法的类名、方法名、入参、出参、总耗时、实际耗时和调用深度 |
| **独立存储** | 业务日志 (API/AUDIT/SYSTEM) 与 ACCESS 日志使用**独立 MQ Topic** 和**独立分表**，互不干扰 |
| **异步 MQ 发布** | 生产端通过 `LogPublisher` 委托双车道 `LogDispatcher` 异步发布，内置有界队列、线程池、熔断、降级和可选本地兜底，业务线程不承担 MQ I/O。底层通过 `cy-common-mq` 投递到 Redis Stream / Kafka 等驱动 |
| **响应式支持** | 当方法返回 `Mono` / `Flux` 时，`@SysLog` 和 `@AccessLog` 自动将耗时、成功/失败/取消、发布等逻辑延迟到流真正完成时，并通过 Reactor Context → ThreadLocal 桥接保持 `traceId`、用户、`requestMethod`、`requestUri` 连续性。`Mono` 还可采集 afterData/changeSummary；`Flux` 为多值流，仅记录成功/失败/取消/耗时（需要类路径存在 `reactor-core`） |
| **自动分表** | 按月 + 行数分表，单表满自动创建新表（如 `sys_log_202601_001` → `sys_log_202601_002`） |
| **真实批量写入** | MQ 监听器按批拉取消息，持久化阶段按目标表分组后执行单次 SQL 批量 `INSERT ... VALUES (...), (...)`，避免“先缓冲再逐条 insert”的伪批量 |
| **发布失败不阻断业务** | 发布阶段固定异步执行，失败仅记录错误和指标，不影响主业务流程；持久化阶段支持重试、退避、死信回退、自动回放和 parking lot |
| **SQL 级分页** | 跨分表分页查询使用 SQL LIMIT/OFFSET 逐表偏移算法，避免将全量数据加载到内存导致 OOM |
| **采样控制** | ACCESS 日志支持采样率（0.0 ~ 1.0），采样决策在请求入口做出，同一请求内所有 span 保持一致（全量或全弃） |
| **自动清理** | 定时清理过期分表（直接 DROP TABLE），业务日志与 ACCESS 日志**独立保留策略**，支持 Redis 分布式锁保证集群安全 |
| **Micrometer 指标** | 当类路径存在 `micrometer-core` 时自动注册发布/持久化统计和发布车道治理指标，无 Micrometer 时不影响功能 |
| **敏感数据脱敏** | 支持配置敏感字段名（如 password/token），自动对请求/响应体和数据变更内容进行脱敏 |
| **SQL 注入防护** | 所有动态生成的表名均通过正则校验（仅允许 `[a-zA-Z0-9_]`），防止恶意表名注入 |
| **请求体有界缓存** | Servlet 环境自动将 `HttpServletRequest` 包装为 `BoundedContentCachingRequestWrapper`，仅缓存 `cy.log.max-payload-bytes`（默认 64KB）字节，超出部分透传不缓存；multipart / 二进制请求完全不缓存（仅记录文件元信息），杜绝大文件上传触发 OOM；排除路径（actuator 等）自动跳过 |
| **载荷统一治理** | 所有日志类型（API / SysLog / Access / 编程式 / 响应式）的"对象 → 安全文本"统一走 `LogPayloadProcessor`，自动施加文件元信息识别、类型黑名单、深度 / 集合 / 字符串硬约束、敏感字段脱敏与展示截断；发布前再由 `LogRecordNormalizer` 兜底归一化（即便上游绕过处理器也能保证字段不超长） |
| **本地文件日志** | ACCESS 日志可选写入本地 `access.log` 文件（管道分隔格式），便于 awk/grep 离线分析 |
| **分布式链路串联** | 自动从 `InvocationContext` 获取 `traceId` / `spanId` / `parentSpanId` / `requestMethod` / `requestUri`，并在 `@AccessLog` 作用域内派生“当前活动 span”上下文，与 `cy-common-starter-web` 的 Servlet / WebFlux 调用上下文传播机制对接 |

---

## 2. 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java** | 21+ | 使用 record、pattern matching 等语法特性 |
| **Spring Boot** | 3.x | 核心层（`@SysLog`、`@AccessLog`、`LogService`）优先复用 `InvocationContext` 中的 trace / 用户 / requestMethod / requestUri / clientIp，缺失时再由 `RequestInfoResolver` 兜底。零侵入 API 日志（`ApiLogAspect`）仅限 Servlet 环境 |
| **reactor-core**（可选） | 3.6+ | 当方法返回 `Mono`/`Flux` 时，自动将日志采集延迟到流完成时，并启用 Reactor Context 桥接恢复调用上下文。无 reactor-core 时对同步方法无任何影响 |
| **cy-common-core** | 同版本 | 提供 `InvocationContext`（用户上下文、traceId、requestMethod/requestUri） |
| **cy-common-mq** | 同版本 | 提供 `MqTemplate`、`@MqListener`（默认实现为 Redis Stream） |
| **cy-common-orm**（可选） | 同版本 | 仅持久化节点需要，提供 MyBatis-Flex 和数据源 |
| **MySQL** | 5.7+ / 8.0+ | 持久化目标数据库 |
| **Redis** | 6.0+ | MQ 驱动（Redis Stream）+ 分布式锁（清理任务） |
| **Micrometer**（可选） | 1.12+ | 启用后自动注册指标 |

---

## 3. 引入依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-log</artifactId>
</dependency>
```

组件通过 Spring Boot `AutoConfiguration.imports` 自动注册，无需手动 `@Import`。

### 3.1 最小接入路径

README 现有内容已经覆盖了完整配置、示例和架构；如果你是第一次接入，建议先按下面的最短路径验证，再按需阅读后续章节。

| 场景 | 最小配置 | 结果 |
|------|---------|------|
| **业务服务：只负责产生日志** | `cy.mq.driver=REDIS`（或 `KAFKA`） | 启用 `ApiLogAspect`（Servlet）、`@SysLog`、`@AccessLog`、`LogService`，日志异步投递到 MQ，不落库 |
| **日志中心：负责消费 / 查询 / 清理** | `cy.mq.driver=REDIS`（或 `KAFKA`） + `cy.log.publish.enabled=false` + `cy.log.persistence.enabled=true` | 不装配发布切面和发布线程池，只启动持久化、查询、清理相关 Bean |
| **需要本地 access.log 文件** | `cy.log.access.enable-log-file=true` + 引入 `cy-log-defaults.xml` | ACCESS span 除 MQ 外还会写入本地 `access.log`，便于离线排查 |

### 3.2 Logback 接入（可选但推荐）

模块内置了 `cy-log-defaults.xml`，提供两类默认能力：

- 控制台日志模式 `CY_LOG_PATTERN`，默认带 `traceId` / `spanId`
- `log.access` logger，对应滚动文件 `access.log`

若服务希望直接复用这套默认输出，可在 `logback-spring.xml` 中引入：

```xml
<configuration>
    <include resource="cy-log-defaults.xml"/>

    <!-- 如果你的控制台 appender 使用 CONSOLE_LOG_PATTERN，可直接复用组件提供的 trace/span 模式 -->
    <property name="CONSOLE_LOG_PATTERN" value="${CY_LOG_PATTERN}"/>
</configuration>
```

> `cy.log.access.enable-log-file=true` 只负责让组件额外向 `log.access` logger 输出内容；真正落到 `access.log` 文件，还需要上面的 `logback-spring.xml` 引入生效。

---

## 4. 配置说明

所有配置项均以 `cy.log` 为前缀。标注 **必须** 的项没有可用的默认值，需要显式配置；其余项均有默认值，可按需覆盖。

### 4.1 全局配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.enabled` | boolean | `true` | 全局开关。设为 `false` 时完全禁用日志组件，不创建任何 Bean |
| `cy.log.audit-header` | String | `X-Audit-Required` | 审计日志触发请求头。当请求头中该字段值为 `"true"` 时，API 日志自动升级为 AUDIT 类型 |
| `cy.log.exclude-patterns` | List | `/actuator/**`, `/swagger-ui/**`, `/v3/api-docs/**`, `/favicon.ico`, `/error` | 排除的 URL 路径模式（Ant 风格）。匹配这些路径的请求不记录日志，也不做请求体缓存 |
| `cy.log.record-request-body` | boolean | `true` | 是否记录请求体。大文件上传等场景可设为 `false` |
| `cy.log.record-response-body` | boolean | `false` | 是否记录响应体。默认关闭以减少日志数据量 |
| `cy.log.max-body-length` | int | `2048` | 请求体/响应体最大记录长度（字符数），超长截断 |
| `cy.log.sensitive-fields` | List | `password`, `pwd`, `secret`, `token`, `accessToken`, `refreshToken`, `credential` | 敏感字段名列表（不区分大小写），匹配的字段值替换为 `******` |

### 4.2 调用链路日志 (ACCESS) 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.access.enabled` | boolean | `true` | ACCESS 日志开关。仅在生产端发布平面启用时装配 `AccessLogAspect` |
| `cy.log.access.persist` | boolean | `true` | 是否启用 ACCESS 持久化监听器。仅当 `cy.log.persistence.enabled=true` 且该值为 `true` 时才创建 `AccessLogPersistenceListener`；设为 `false` 时 ACCESS 日志仅发布到 MQ |
| `cy.log.access.sample-rate` | double | `0.1` | 采样率，取值范围 `0.0` ~ `1.0`。采样决策在请求入口做出，同一请求的所有 span 一致 |
| `cy.log.access.detail-mode` | enum | `ROOT_ONLY` | 正常状态下的明细输出模式 |
| `cy.log.access.detail-sample-rate` | double | `0.1` | `SAMPLED_SPANS` 模式下的明细 span 额外采样率 |
| `cy.log.access.pressure-detail-mode` | enum | `ERROR_ONLY` | 压力降级状态下的明细输出模式 |
| `cy.log.access.emergency-detail-mode` | enum | `ROOT_ONLY` | 紧急降级状态下的明细输出模式 |
| `cy.log.access.record-input-under-pressure` | boolean | `false` | 压力状态下是否允许记录入参明细 |
| `cy.log.access.record-output-under-pressure` | boolean | `false` | 压力状态下是否允许记录出参明细 |
| `cy.log.access.enable-log-file` | boolean | `false` | 是否同时写入本地 `access.log` 文件（管道分隔格式），可用于 awk/grep 离线分析 |
| `cy.log.access.default-req-char-limit` | int | `2048` | ACCESS 日志全局入参字符限制。`-1` 不限制。注解上的 `reqCharLimit` 优先级更高 |
| `cy.log.access.default-ret-char-limit` | int | `2048` | ACCESS 日志全局出参字符限制。`-1` 不限制。注解上的 `retCharLimit` 优先级更高 |
| `cy.log.access.topic` | String | `cy:log:access` | ACCESS 日志的 MQ Topic（独立于业务日志） |
| `cy.log.access.delivery-mode` | enum | `STREAM` | ACCESS 日志 MQ 投递模式 |
| `cy.log.access.group-id` | String | `log-access-persistence` | ACCESS 日志持久化消费组 ID |
| `cy.log.access.dead-letter-topic` | String | `cy:log:access:dlq` | ACCESS 持久化在重试耗尽后的死信 Topic |
| `cy.log.access.dead-letter-replay-group-id` | String | `log-access-dlq-replay` | ACCESS 死信自动回放消费组 ID |
| `cy.log.access.dead-letter-parking-lot-topic` | String | `cy:log:access:dlq:parking` | ACCESS 死信达到最大回放次数后的停放 Topic |
| `cy.log.access.table-prefix` | String | `access_log` | ACCESS 日志表名前缀（独立于业务日志），生成表名如 `access_log_202601_001` |
| `cy.log.access.max-rows-per-table` | int | `1000000` | ACCESS 日志单表最大行数，达到后自动创建新表 |
| `cy.log.access.retention-days` | int | `30` | ACCESS 日志保留天数（通常比业务日志短） |

### 4.3 发布治理配置

> **说明**：`cy.log.publish.enabled=false` 时关闭生产端发布平面，不创建 `LogPublisher`、`LogService`、`@SysLog` / `@AccessLog` / `ApiLogAspect`、Servlet 请求缓存过滤器、发布车道线程池以及 Reactor 上下文桥接；持久化、查询、清理配置不受影响。

#### 4.3.1 发布平面开关

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.publish.enabled` | boolean | `true` | 是否启用生产端发布能力 |

#### 4.3.2 业务日志车道

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.publish.business.enabled` | boolean | `true` | 是否启用业务日志发布车道 |
| `cy.log.publish.business.core-threads` | int | `2` | 核心工作线程数 |
| `cy.log.publish.business.max-threads` | int | `4` | 最大工作线程数 |
| `cy.log.publish.business.keep-alive-ms` | long | `60000` | 空闲线程保活时长 |
| `cy.log.publish.business.queue-capacity` | int | `2048` | 有界队列容量 |
| `cy.log.publish.business.fallback-on-reject` | boolean | `true` | 队列满时是否尝试本地兜底 |
| `cy.log.publish.business.fallback-on-failure` | boolean | `true` | 发布失败时是否尝试本地兜底 |

#### 4.3.3 ACCESS 日志车道

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.publish.access.enabled` | boolean | `true` | 是否启用 ACCESS 日志发布车道 |
| `cy.log.publish.access.core-threads` | int | `1` | 核心工作线程数 |
| `cy.log.publish.access.max-threads` | int | `2` | 最大工作线程数 |
| `cy.log.publish.access.keep-alive-ms` | long | `60000` | 空闲线程保活时长 |
| `cy.log.publish.access.queue-capacity` | int | `8192` | 有界队列容量 |
| `cy.log.publish.access.fallback-on-reject` | boolean | `false` | 队列满时是否尝试本地兜底 |
| `cy.log.publish.access.fallback-on-failure` | boolean | `false` | 发布失败时是否尝试本地兜底 |

#### 4.3.4 熔断、降级与本地兜底

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.publish.breaker.window-ms` | long | `30000` | 熔断滑动窗口长度 |
| `cy.log.publish.breaker.minimum-calls` | int | `20` | 熔断判定最少调用次数 |
| `cy.log.publish.breaker.consecutive-failures` | int | `8` | 连续失败阈值 |
| `cy.log.publish.breaker.failure-rate-threshold` | double | `0.5` | 失败率阈值，取值范围 `0.0` ~ `1.0` |
| `cy.log.publish.breaker.open-duration-ms` | long | `30000` | 熔断打开后的保持时长 |
| `cy.log.publish.breaker.half-open-permits` | int | `5` | 半开状态允许的探测请求数 |
| `cy.log.publish.degrade.enabled` | boolean | `true` | 是否启用基于队列压力的动态降级 |
| `cy.log.publish.degrade.pressure-queue-usage` | double | `0.70` | 进入压力降级的队列使用率阈值 |
| `cy.log.publish.degrade.emergency-queue-usage` | double | `0.90` | 进入紧急降级的队列使用率阈值 |
| `cy.log.publish.fallback.enabled` | boolean | `false` | 是否启用本地兜底存储实现 |
| `cy.log.publish.fallback.directory` | String | `logs/cy-log-fallback` | 兜底目录 |
| `cy.log.publish.fallback.max-file-size-bytes` | long | `16777216` | 单文件最大大小 |
| `cy.log.publish.fallback.max-files` | int | `16` | 最多保留文件数 |

### 4.4 MQ 配置（业务日志 API/AUDIT/SYSTEM）

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.mq.topic` | String | `cy:log:record` | 业务日志的 MQ Topic |
| `cy.log.mq.delivery-mode` | enum | `STREAM` | 业务日志 MQ 投递模式 |
| `cy.log.mq.group-id` | String | `log-persistence` | 业务日志持久化消费组 ID |
| `cy.log.mq.dead-letter-topic` | String | `cy:log:record:dlq` | 业务日志持久化在重试耗尽后的死信 Topic |
| `cy.log.mq.dead-letter-replay-group-id` | String | `log-dlq-replay` | 业务日志死信自动回放消费组 ID |
| `cy.log.mq.dead-letter-parking-lot-topic` | String | `cy:log:record:dlq:parking` | 业务日志死信达到最大回放次数后的停放 Topic |

### 4.5 持久化配置（业务日志 API/AUDIT/SYSTEM）

> **注意**：持久化默认**不启用**。只有负责存储日志的服务节点（如 admin-service）需要开启。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.persistence.enabled` | boolean | `false` | **必须显式设为 `true`** 才会启用持久化。启用后创建 LogMapper、LogTableRouter、LogPersistenceListener 等 Bean |
| `cy.log.persistence.table-prefix` | String | `sys_log` | 业务日志表名前缀，生成表名如 `sys_log_202601_001` |
| `cy.log.persistence.max-rows-per-table` | int | `1000000` | 业务日志单表最大行数 |
| `cy.log.persistence.batch-size` | int | `100` | 单次数据库批量写入条数。超出后会拆成多个 SQL batch，但每个 batch 仍为真实批量 `INSERT` |
| `cy.log.persistence.max-retry-attempts` | int | `3` | 持久化最大重试次数，包含首次执行 |
| `cy.log.persistence.retry-backoff-ms` | long | `300` | 持久化重试退避时间，单位毫秒 |
| `cy.log.persistence.dead-letter-enabled` | boolean | `true` | 重试耗尽后是否写入死信 Topic。关闭后最终失败会直接抛出异常，由 MQ 驱动决定是否重投 |
| `cy.log.persistence.dead-letter-replay-enabled` | boolean | `true` | 是否启用死信自动回放。关闭后死信会直接转入 parking lot |
| `cy.log.persistence.dead-letter-replay-max-attempts` | int | `6` | 死信自动回放最大尝试次数（达到上限后转入 parking lot） |
| `cy.log.persistence.dead-letter-replay-backoff-ms` | long | `5000` | 死信自动回放退避时间，单位毫秒 |

### 4.6 自动清理配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.log.cleanup.enabled` | boolean | `false` | **必须显式设为 `true`** 才会启用自动清理任务 |
| `cy.log.cleanup.retention-days` | int | `180` | **业务日志** (API/AUDIT/SYSTEM) 保留天数。ACCESS 日志保留天数在 `access.retention-days` 中单独配置 |
| `cy.log.cleanup.cron` | String | `0 0 2 * * ?` | 清理任务 Cron 表达式，默认每天凌晨 2 点执行 |
| `cy.log.cleanup.cluster-safe` | boolean | `true` | 是否使用 Redis 分布式锁保证集群中只有一个实例执行清理 |
| `cy.log.cleanup.lock-key` | String | `cy:log:cleanup:lock` | 分布式锁的 Redis Key |
| `cy.log.cleanup.lock-ttl-seconds` | int | `600` | 分布式锁 TTL（秒） |

### 4.7 配置示例

> **建议**：生产环境显式配置 `cy.mq.driver=REDIS` 或 `KAFKA`。`cy-common-mq` 不再隐式默认到 `LOCAL`。
>
> **补充**：如果服务节点只负责日志消费、查询或清理，不承担生产端发布职责，可额外配置 `cy.log.publish.enabled=false` 关闭发布切面和发布线程池。

#### 日志生产节点（业务服务）— 只发布，不存储

```yaml
# 大多数业务服务只需这些配置，其余全部使用默认值
cy:
  mq:
    driver: REDIS              # 生产环境必须显式指定跨进程 MQ 驱动
  log:
    enabled: true
    access:
      sample-rate: 0.5          # 生产环境建议降低 ACCESS 采样率
```

#### 日志消费节点（admin-service）— 发布 + 存储 + 清理

```yaml
cy:
  mq:
    driver: REDIS
  log:
    enabled: true
    mq:
      dead-letter-topic: cy:log:record:dlq
      dead-letter-replay-group-id: log-dlq-replay
      dead-letter-parking-lot-topic: cy:log:record:dlq:parking
    persistence:
      enabled: true             # 启用持久化（必须）
      table-prefix: sys_log
      max-rows-per-table: 1000000
      batch-size: 100
      max-retry-attempts: 3
      retry-backoff-ms: 300
      dead-letter-enabled: true
      dead-letter-replay-enabled: true
      dead-letter-replay-max-attempts: 6
      dead-letter-replay-backoff-ms: 5000
    access:
      persist: true             # ACCESS 日志也入库
      dead-letter-topic: cy:log:access:dlq
      dead-letter-replay-group-id: log-access-dlq-replay
      dead-letter-parking-lot-topic: cy:log:access:dlq:parking
      table-prefix: access_log
      max-rows-per-table: 1000000
      retention-days: 30
    cleanup:
      enabled: true             # 启用自动清理（必须）
      retention-days: 180       # 业务日志保留 180 天
      cron: "0 0 2 * * ?"
      cluster-safe: true
```

#### 仅本地落 `access.log`（不改持久化拓扑）

```yaml
cy:
  log:
    access:
      enable-log-file: true
```

同时在服务的 `logback-spring.xml` 中加入：

```xml
<configuration>
    <include resource="cy-log-defaults.xml"/>
</configuration>
```

---

## 5. 日志类型

| 类型 | 枚举值 | 说明 | 产生方式 |
|------|--------|------|---------|
| **API** | `LogType.API` | 接口访问日志 | 自动拦截 Controller（零侵入） |
| **AUDIT** | `LogType.AUDIT` | 审计日志 | `@SysLog(logType=AUDIT)` 或 API 日志通过 `X-Audit-Required` 头升级 |
| **SYSTEM** | `LogType.SYSTEM` | 系统操作日志 | `@SysLog` 注解或 `LogService` 编程式 API |
| **ACCESS** | `LogType.ACCESS` | 调用链路日志 | `@AccessLog` 注解自动记录 |

- **API / AUDIT / SYSTEM** 为业务日志，共享一个 MQ Topic，存储到 `sys_log_*` 分表。
- **ACCESS** 为调用链路日志，使用独立的 MQ Topic，存储到 `access_log_*` 分表。

---

## 6. 使用场景与示例

### 6.1 零侵入 API 日志（Servlet 环境专属）

**场景**：自动记录所有 Controller 接口的请求/响应信息，用于接口监控和问题排查。

**使用方式**：Servlet 环境（Tomcat / Undertow / Jetty）引入依赖即生效，无需任何注解。WebFlux / gRPC 环境下此切面不自动激活。

```java
@RestController
@RequestMapping("/system/users")
public class UserController {
    // 所有接口自动记录 API 日志：URI、参数、耗时、用户信息、响应状态码
    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.getById(id);
    }
}
```

自动记录内容包括：请求方法、URI、查询参数、请求体（可选）、响应状态码、耗时、客户端 IP、用户 ID/用户名、traceId 等。

### 6.2 @SysLog 注解 — 业务操作日志

**场景**：需要记录具有业务含义的操作日志，用于审计、操作追溯、数据变更追踪。

```java
@Service
public class UserService {

    /**
     * 简单操作日志
     */
    @SysLog(module = "\u7528\u6237\u7ba1\u7406", operation = "\u65b0\u589e\u7528\u6237", description = "\u65b0\u589e\u7528\u6237")
    public void createUser(UserDTO dto) {
        // \u4e1a\u52a1\u903b\u8f91
    }

    /**
     * 带数据变更对比的审计日志
     * - recordBefore=true + beforeDataEl: 方法执行前通过 SpEL 查询原始数据
     * - recordAfter=true: 方法执行后记录返回值
     * - logType=AUDIT: 标记为审计日志
     * - 同时有 before/after 数据时，自动生成变更摘要（changeSummary）
     */
    @SysLog(
        module = "用户管理",
        operation = "修改用户",
        targetType = "User",
        targetIdEl = "#dto.id",
        recordBefore = true,
        beforeDataEl = "@userService.getById(#dto.id)",
        recordAfter = true,
        logType = LogType.AUDIT
    )
    public User updateUser(UserDTO dto) {
        // 方法执行前自动通过 beforeDataEl 查询原始 User
        // 方法执行后自动记录返回值作为修改后数据
        // 自动 diff 生成变更摘要：如 "nickname: 张三→李四, status: 1→0"
        return doUpdate(dto);
    }

    /**
     * recordBefore 不指定 beforeDataEl 时，回退到序列化方法入参
     */
    @SysLog(
        module = "用户管理",
        operation = "删除用户",
        targetType = "User",
        targetIdEl = "#id",
        recordBefore = true,
        logType = LogType.AUDIT
    )
    public void deleteUser(Long id) {
        userMapper.deleteById(id);
    }
}
```

当请求入口已经通过 `InvocationContextFilter` / `InvocationContextWebFilter` 建立调用上下文时，`@SysLog` 会优先复用 `InvocationContext` 中的 `requestMethod` / `requestUri` / `clientIp` / 用户信息；仅在字段缺失时才回退 `RequestInfoResolver`。

### 6.3 LogService 编程式 API — 灵活的手动日志

**场景**：需要在复杂业务逻辑中灵活记录日志，注解方式无法满足时使用。

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final LogService logService;

    public void processRefund(Long orderId, BigDecimal amount) {
        Order order = orderMapper.selectById(orderId);
        Order oldOrder = BeanUtil.copyProperties(order, Order.class);

        try {
            order.setStatus("REFUNDED");
            orderMapper.updateById(order);

            // 方式一：简单记录
            logService.log("订单管理", "退款", "订单 " + orderId + " 退款 " + amount + " 元");

            // 方式二：记录数据变更
            logService.logChange("订单管理", "退款", "Order", orderId.toString(), oldOrder, order);

            // 方式三：Builder 模式（最灵活）
            logService.builder()
                .logType(LogType.AUDIT)
                .module("订单管理")
                .operation("退款")
                .targetType("Order")
                .targetId(orderId.toString())
                .beforeData(oldOrder)
                .afterData(order)
                .description("用户申请退款，金额：" + amount)
                .success(true)
                .record();

        } catch (Exception e) {
            logService.builder()
                .module("订单管理")
                .operation("退款")
                .targetId(orderId.toString())
                .description("退款失败")
                .success(false)
                .errorMessage(e.getMessage())
                .record();
            throw e;
        }
    }
}
```

`LogService` 与 `@SysLog` 共用同一套基础记录构建逻辑，因此在响应式链路或异步回调中，只要入口层已建立 `InvocationContext`，就能保持 request / user / trace 连续性。

### 6.4 @AccessLog — 调用链路日志

**场景**：需要记录方法级调用链路，追踪嵌套调用（A → B → C），定位性能瓶颈。

```java
/**
 * 标注在类上：该类所有 public 方法均记录调用链路
 */
@AccessLog
@Service
public class OrderService {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;

    public Order createOrder(OrderDTO dto) {
        // 1. 扣减库存 → 产生 depth=2 的 span
        inventoryService.deduct(dto.getSkuId(), dto.getQuantity());
        // 2. 发起支付 → 产生 depth=2 的 span
        paymentService.charge(dto.getUserId(), dto.getAmount());
        // 3. 创建订单
        return doCreate(dto);
    }
}

@AccessLog
@Service
public class InventoryService {

    /**
     * 标注在方法上：可精细控制入参/出参记录
     */
    @AccessLog(reqCharLimit = 2048, retCharLimit = 1024, recordOutput = false)
    public void deduct(Long skuId, int quantity) {
        // 业务逻辑
    }
}
```

上述调用产生的调用链路日志结构：

```
depth=1: OrderService.createOrder        (总耗时 150ms)
  depth=2: InventoryService.deduct       (耗时 30ms)
  depth=2: PaymentService.charge         (耗时 80ms)
```

所有 span 共享同一 `traceId`，每个 span 拥有独立 `spanId`，其 `parentSpanId` 指向直接父 span 的 `spanId`。查询侧会优先按这套身份模型重建调用树。

对于 `Mono` / `Flux` 返回值，组件会把 `AccessTraceState` 与当前活动 span 对应的 `InvocationContext` 一并传播到 Reactor Context；cancel 会记录为 `success=false`，父子 span 的 `childDuration` / `actualDuration` 在 span 完成时统一回填。

如果业务代码会主动切换到线程池、虚拟线程或其他异步执行器中继续调用带 `@AccessLog` 的方法，需要显式传播上下文。例如：

```java
Thread.startVirtualThread(
    AccessLogContext.wrapWithParentDepth(
        InvocationContextAccessor.wrap(() -> {
            orchestrator.orchestrate(request, context);
        })
    )
);
```

`AccessLogContext.wrapWithParentDepth(...)` 用于传播当前 ACCESS trace 状态，并为异步子任务预留发布时机；`InvocationContextAccessor.wrap(...)` 用于同时传播 `traceId`、用户、`requestMethod`、`requestUri`，以及当前活动 span 的 `spanId` / `parentSpanId` 等调用上下文。若只切换线程而不做这两层包装，异步子链路可能无法并入同一条 trace。

### 6.5 @AccessIntercept — 自定义调用链路拦截

**场景**：需要在调用链路日志记录前后插入自定义逻辑（如添加业务标签）。

```java
public class AuditInterceptor implements AccessInterceptor {
    @Override
    public void before(AccessIntercept annotation, AccessSpan span) {
        span.setModule("审计模块");
    }

    @Override
    public void complete(AccessIntercept annotation, AccessSpan span) {
        // 调用完成后的自定义逻辑
    }
}

@Service
public class SensitiveService {

    @AccessLog
    @AccessIntercept(module = "敏感操作", operationDescribe = "查看敏感数据", interceptor = AuditInterceptor.class)
    public SensitiveData viewData(Long id) {
        return sensitiveMapper.selectById(id);
    }
}
```

### 6.6 日志查询 — 分页查询与链路追踪

**场景**：前端/后台管理界面查询日志。

```java
@RestController
@RequiredArgsConstructor
public class LogController {

    private final LogQueryService logQueryService;

    /**
     * 分页查询日志（支持所有类型或按类型筛选）
     */
    @GetMapping("/api/logs")
    public PageResult<LogRecord> query(
            @RequestParam(required = false) LogType logType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        return logQueryService.queryLogs(
            new LogQueryRequest(null, null, logType, null, null, null, null, null, page, size)
        );
    }

    /**
     * 查询链路详情（前端调用树可视化）
     */
    @GetMapping("/api/logs/trace/{traceId}")
    public TraceDetailVO traceDetail(@PathVariable String traceId) {
        return logQueryService.queryTraceDetail(traceId);
    }
}
```

`queryTraceDetail` 返回结构化的 `TraceDetailVO`，包含：
- **traceId / totalDuration / success / serviceName / requestUri / requestMethod / clientIp**：入口链路概览
- **spanTree**：树形调用结构（父子关系 + 瀑布流时间偏移）
- **summary**：总 span 数、失败数、最大深度、瓶颈方法
- **user**：操作用户信息

---

## 7. 架构

```
┌─────────────────────────── 生产者节点（所有业务服务） ───────────────────────────┐
│                                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌───────────┐   ┌──────────────────┐     │
│  │ ApiLogAspect │   │ SysLogAspect │   │ LogService│   │ AccessLogAspect  │     │
│  │  (零侵入)    │   │ (@SysLog)    │   │ (编程式)  │   │  (@AccessLog)    │     │
│  └──────┬───────┘   └──────┬───────┘   └─────┬─────┘   └────────┬─────────┘     │
│         └──────────────────┴─────────────────┘                   │               │
│                             │                                    │               │
│                  ┌──────────▼───────────────────────────────────────────────┐     │
│                  │ LogPublisher                                            │     │
│                  │  ├─ business: LogDispatcher（队列/线程池/熔断/降级）     │     │
│                  │  └─ access:   LogDispatcher（队列/线程池/熔断/降级）     │     │
│                  └──────────┬───────────────────────────────┬──────────────┘     │
│                             │                               │                      │
│                   ┌─────────▼──────────┐         ┌──────────▼──────────┐          │
│                   │ MQ Topic:          │         │ MQ Topic:           │          │
│                   │ cy:log:record      │         │ cy:log:access       │          │
│                   └─────────┬──────────┘         └──────────┬──────────┘          │
└─────────────────────────────┼────────────────────────────────────┼───────────────┘
                              │                                    │
┌─────────────────────────────┼────────────────────────────────────┼───────────────┐
│                     消费者节点（admin-service / 持久化服务）                      │
│                              │                                    │               │
│                   ┌──────────▼──────────┐            ┌────────────▼───────────┐   │
│                   │LogPersistenceListener│            │AccessLogPersistenceListener│
│                   │ 批量消费 → SQL Batch │            │ 批量消费 → SQL Batch   │   │
│                   └──────────┬──────────┘            └────────────┬───────────┘   │
│                              │                                    │               │
│                   ┌──────────▼──────────┐            ┌────────────▼───────────┐   │
│                   │ businessTableRouter │            │ accessTableRouter      │   │
│                   │ sys_log_YYYYMM_NNN │            │ access_log_YYYYMM_NNN │   │
│                   └─────────────────────┘            └────────────────────────┘   │
│                                                                                   │
│                   ┌──────────────────────────┐   ┌─────────────────────┐          │
│                   │ LogCleanupService        │   │ LogQueryServiceImpl │          │
│                   │ 按保留策略清理两类分表    │   │ SQL级分页 + 链路查询│          │
│                   └──────────────────────────┘   └─────────────────────┘          │
└───────────────────────────────────────────────────────────────────────────────────┘
```

当前版本有 5 个关键实现约束：
- **发布治理双车道**：`LogPublisher` 将业务日志与 ACCESS 日志分别交给独立 `LogDispatcher`，每条车道都有自己的有界队列、线程池、熔断、降级和本地兜底策略
- **统一调用上下文**：`InvocationContextFilter` / `InvocationContextWebFilter` 在请求入口写入服务入口级 `traceId`、`spanId`、`requestMethod`、`requestUri`；`@AccessLog` 会在方法作用域内派生当前活动 span 的 `spanId` / `parentSpanId`
- **响应式上下文桥接**：`ReactorLogContextBridge` 在 Reactor 信号执行时恢复 `InvocationContext`、`AccessTraceState` 和 MDC
- **ACCESS 状态模型**：`AccessLogAspect` 通过 `AccessTraceState` 统一维护 span 栈、顺序列表和发布状态，避免响应式链路下状态分裂
- **持久化恢复链路**：正常消费失败后依次进入「重试 → DLQ → 自动回放 → parking lot」

---

## 8. 数据库表结构

业务日志表 (`sys_log_*`) 和 ACCESS 日志表 (`access_log_*`) 使用**相同的表结构**，由组件自动创建：

| 列名 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT PK | 雪花算法 ID |
| `trace_id` | VARCHAR(64) | 链路追踪 ID（索引） |
| `span_id` | VARCHAR(64) | Span ID（索引） |
| `parent_span_id` | VARCHAR(64) | 父 Span ID（索引） |
| `source` | VARCHAR(32) | 请求来源（external/gateway/internal/system） |
| `log_type` | VARCHAR(16) | 日志类型：API / AUDIT / SYSTEM / ACCESS（索引） |
| `service_name` | VARCHAR(64) | 服务名 |
| `server_ip` | VARCHAR(64) | 服务器 IP |
| `request_method` | VARCHAR(16) | HTTP 方法 |
| `request_uri` | VARCHAR(512) | 请求 URI（索引，前 255 字符） |
| `request_params` | TEXT | 请求参数 JSON |
| `request_body` | TEXT | 请求体 |
| `request_headers` | TEXT | 关键请求头 |
| `client_ip` | VARCHAR(64) | 客户端 IP |
| `user_agent` | VARCHAR(512) | UA |
| `response_status` | INT | HTTP 响应状态码 |
| `response_body` | TEXT | 响应体 |
| `duration` | BIGINT | 耗时（ms） |
| `user_id` | BIGINT | 用户 ID（索引） |
| `username` | VARCHAR(64) | 用户名 |
| `nickname` | VARCHAR(64) | 昵称 |
| `module` | VARCHAR(64) | 业务模块 |
| `operation` | VARCHAR(64) | 操作类型 |
| `target_type` | VARCHAR(64) | 对象类型 |
| `target_id` | VARCHAR(64) | 对象 ID |
| `before_data` | TEXT | 修改前数据 |
| `after_data` | TEXT | 修改后数据 |
| `description` | VARCHAR(512) | 操作描述 |
| `change_summary` | VARCHAR(1024) | 变更摘要 |
| `class_name` | VARCHAR(256) | 调用类名（ACCESS） |
| `method_name` | VARCHAR(128) | 调用方法名（ACCESS） |
| `depth` | INT | 调用栈深度（ACCESS） |
| `actual_duration` | BIGINT | 实际执行耗时（ACCESS） |
| `input_args` | TEXT | 方法入参 JSON（ACCESS） |
| `return_value` | TEXT | 方法返回值 JSON（ACCESS） |
| `success` | TINYINT(1) | 是否成功 |
| `error_message` | VARCHAR(1024) | 错误消息 |
| `stack_trace` | TEXT | 异常堆栈 |
| `create_time` | BIGINT | 创建时间戳（索引） |

---

## 9. Micrometer 指标

当类路径存在 `micrometer-core`（如 Spring Boot Actuator）时自动注册以下 Gauge 视图：

| 指标名 | 标签 | 说明 |
|--------|------|------|
| `cy.log.publish.success` | 无 | 业务日志发布成功数 |
| `cy.log.publish.fail` | 无 | 业务日志发布失败数 |
| `cy.log.persist.success` | 无 | 业务日志持久化成功数 |
| `cy.log.persist.fail` | 无 | 业务日志持久化失败数 |
| `cy.log.access.publish.success` | 无 | ACCESS 日志发布成功数 |
| `cy.log.access.publish.fail` | 无 | ACCESS 日志发布失败数 |
| `cy.log.access.persist.success` | 无 | ACCESS 日志持久化成功数 |
| `cy.log.access.persist.fail` | 无 | ACCESS 日志持久化失败数 |
| `cy.log.access.sampled.out` | 无 | ACCESS 被采样丢弃数 |
| `cy.log.dispatch.accepted` | 无 | 业务日志被发布平面接收的次数 |
| `cy.log.access.dispatch.accepted` | 无 | ACCESS 日志被发布平面接收的次数 |
| `cy.log.lane.accepted` | `lane=business|access` | 车道接收成功次数 |
| `cy.log.lane.dropped` | `lane=business|access` | 车道丢弃次数 |
| `cy.log.lane.queue.rejected` | `lane=business|access` | 队列满导致的拒绝次数 |
| `cy.log.lane.breaker.rejected` | `lane=business|access` | 熔断打开导致的拒绝次数 |
| `cy.log.lane.fallback.success` | `lane=business|access` | 实际尝试本地兜底且成功的次数 |
| `cy.log.lane.fallback.fail` | `lane=business|access` | 实际尝试本地兜底但失败的次数 |
| `cy.log.lane.queue.depth` | `lane=business|access` | 当前队列深度 |
| `cy.log.lane.queue.capacity` | `lane=business|access` | 当前队列容量 |
| `cy.log.lane.inflight` | `lane=business|access` | 当前执行中的发布任务数 |
| `cy.log.lane.breaker.state` | `lane=business|access` | 熔断器状态编码 |
| `cy.log.lane.degrade.level` | `lane=business|access` | 当前降级等级编码 |
| `cy.log.lane.publish.latency.last` | `lane=business|access` | 最近一次发布耗时 |

无 Micrometer 时，指标仍在内存中通过 `LogMetrics` 维护，可用于内部监控。

---

## 10. 扩展点

| 扩展方向 | 当前实现 | 扩展方式 |
|---------|---------|---------|
| **存储引擎** | MySQL（MyBatis-Flex 动态表名） | 替换 `LogMapper` + `LogTableRouter`，可接入 Elasticsearch / ClickHouse |
| **MQ 驱动** | Redis Stream（`cy-common-mq`） | 替换 `MqTemplate` 实现，可接入 Kafka / RabbitMQ |
| **调用上下文传播** | `InvocationContext` + `ReactorLogContextBridge` | 在自定义入口（MQ、定时任务、gRPC）建立 `InvocationContext`，即可复用统一链路 / 用户 / 请求信息模型 |
| **请求信息兜底** | `RequestInfoResolver` SPI（默认 `ServletRequestInfoResolver`） | 当 `InvocationContext` 中缺少 request 信息时作为兜底，可扩展到其他运行时 |
| **链路拦截** | `@AccessIntercept` + `AccessInterceptor` SPI | 实现 `AccessInterceptor` 接口，注册自定义拦截逻辑 |
| **日志序列化** | `AccessLogSerializer`（注解驱动的 JSON 序列化） | 可扩展序列化策略 |
| **查询服务** | `LogQueryServiceImpl`（SQL 级分页） | 实现 `LogQueryService` 接口，替换为 ES 查询等 |

---

## 11. 术语表

以下是本组件中使用的关键术语解释，帮助理解配置和日志数据的含义。

### Span（跨度）

**Span** 是分布式链路追踪中的基本工作单元，代表一次方法调用的完整生命周期。每个 Span 记录了：
- 哪个类、哪个方法被调用
- 何时开始、耗时多久
- 入参和返回值
- 是否成功、错误信息

在本组件中，每个被 `@AccessLog` 注解标注的方法执行一次，就产生一个 Span。一次用户请求可能触发多个嵌套方法调用（如 Controller → Service → DAO），每个方法对应一个 Span，所有 Span 共享同一个 `traceId` 形成一条完整的调用链。

**示例**：用户调用"创建订单"接口，产生以下 Span：
```
Span 1: OrderController.createOrder    (depth=1, duration=200ms)
  Span 2: OrderService.createOrder     (depth=2, duration=180ms)
    Span 3: InventoryService.deduct    (depth=3, duration=30ms)
    Span 4: PaymentService.charge      (depth=3, duration=80ms)
```

### TraceId（链路追踪 ID）

**TraceId** 是一个全局唯一的标识符（通常是 UUID 或雪花 ID），用于将同一个用户请求触发的所有日志（包括跨服务的日志）关联在一起。

- 在 HTTP 请求进入入口层时生成或续传，通过请求头 (`X-Trace-Id`) 在服务间传递
- 同一请求内所有 API 日志、SYSTEM 日志、ACCESS 日志共享同一个 TraceId
- 通过 `queryByTraceId` 或 `queryTraceDetail` 可以查询某次请求的完整调用链

### SpanId / ParentSpanId（Span ID / 父 Span ID）

- **spanId**：当前调用作用域的 span ID。入口过滤器创建的基础上下文中，它表示“当前服务入口请求”的 span；在 `@AccessLog` 作用域内，它会被派生为“当前活动方法 span”的 ID
- **parentSpanId**：当前调用作用域的父 span ID，始终指向调用方的 `spanId`，用于还原跨服务 / 跨方法父子关系
- 在 HTTP 场景中，入口层会将上游 `X-Span-Id` 作为 `parentSpanId`，并为本服务生成新的入口 `spanId`；`X-Request-Id` 仅保留给业务请求标识 / 幂等等非 tracing 语义；在进程内方法调用中，`@AccessLog` 会继续为每个方法 span 分配独立 `spanId`，并通过 `parentSpanId → spanId` 建立统一调用树

### 采样率（Sample Rate）

**采样率**是控制 ACCESS 日志记录比例的参数，取值范围 `0.0` ~ `1.0`：
- `1.0` = 100% 全量采集，每个请求的调用链路都记录
- `0.5` = 50% 采样，约一半的请求会记录调用链路
- `0.1` = 10% 采样，只记录十分之一的请求
- `0.0` = 完全不采集

**为什么需要采样？** ACCESS 日志记录的是方法级调用链路，数据量远大于普通 API 日志。一个请求可能产生几十甚至上百个 Span。在高并发生产环境中，全量采集会产生海量数据，占用存储和带宽。通过降低采样率，可以在"性能开销"和"链路可观测性"之间取得平衡。

**采样一致性**：采样决策在请求入口处（第一个 `@AccessLog` 方法）做出，同一请求内所有后续的 Span 要么全部记录、要么全部丢弃。这保证了采集到的链路是完整的，不会出现"只记录了一半调用"的残缺数据。

### Depth（调用深度）

**Depth** 表示方法在调用链中的嵌套层级：
- `depth=1`：入口方法（如 Controller）
- `depth=2`：被入口方法直接调用的方法（如 Service）
- `depth=3`：更深层的方法调用

深度上限为 50 层，超过后不再记录，防止无限递归导致内存溢出。

### Duration 与 ActualDuration（总耗时与实际耗时）

- **duration**（总耗时）：方法从开始到结束的完整耗时，包含所有子方法的执行时间
- **actualDuration**（实际耗时）：`duration - 所有子方法的 duration`，即方法自身的执行时间

**示例**：`ServiceA.doWork()` 总耗时 100ms，其中调用 `ServiceB.query()` 花了 60ms。则：
- `ServiceA.doWork()` 的 duration=100ms，actualDuration=40ms
- `ServiceB.query()` 的 duration=60ms，actualDuration=60ms

当前实现中，无论同步还是响应式路径，父级 `actualDuration` 都会在子 span 完成时统一回填。

actualDuration 高的方法就是性能瓶颈所在，`queryTraceDetail` 返回的 `summary.bottleneckMethod` 就是 actualDuration 最大的方法。

### 分表（Table Sharding）

**分表**是将大量数据分散到多个物理表中存储的策略。本组件使用 **月份 + 行数** 双维度分表：
- 按月份：每个月一个分表组，表名如 `sys_log_202601_001`
- 按行数：单表行数达到 `maxRowsPerTable`（默认 100 万）后，自动创建新表 `sys_log_202601_002`

**为什么分表？**
- 单表数据量过大时查询性能急剧下降
- 按月分表便于按时间范围清理（直接 DROP TABLE 比 DELETE 快几个数量级）
- 查询时只扫描时间范围内的分表，减少 IO

### MQ（消息队列）

**MQ** 用于异步传输日志数据。日志产生后不直接写入数据库，而是先发送到 MQ，由专门的消费者（持久化监听器）从 MQ 读取后批量写入。

**好处**：
- 不阻塞业务线程（日志写入是后台操作）
- 生产者和消费者解耦（业务服务不需要连接日志数据库）
- 支持多消费者（可以同时写入数据库和 ES）

本组件默认使用 Redis Stream 作为 MQ 实现，通过 `cy-common-mq` 抽象，可替换为 Kafka、RabbitMQ 等。

---

## 12. 注意事项

1. **生产者与消费者分离**：大多数业务服务只需发布日志（不开启 `persistence.enabled`），只有日志存储服务需要开启持久化和清理。当前仓库中 `orrery-admin-service` 是持久化节点，`orrery-butler-service`、`cy-knowledge-repository` 等业务服务应保持只发布不落库。
2. **采样一致性**：ACCESS 采样决策在请求入口处做出，同一请求内的所有 span 要么全量记录要么全部丢弃，保证链路完整性。
3. **失败处理默认尽量闭环**：持久化失败会先重试，重试耗尽后默认写入死信 Topic；若开启死信自动回放，则继续按照「DLQ → 自动回放 → parking lot」恢复。只有显式关闭 `dead-letter-enabled` 时，最终失败才会向上抛出异常。
4. **发布阶段固定异步 fail-open**：日志发布失败只记录错误与指标，不阻断主业务线程；需要更强可靠性时，应依赖持久化重试、死信与回放链路兜底。
5. **TEXT 列安全**：TEXT 类型列（如 `input_args`、`request_body` 等）在写入前自动截断到 15000 字符，防止 `DataTruncation` 异常。
6. **ThreadLocal 安全**：同步路径下，`AccessLogContext` / `InvocationScope` 在请求结束后通过 `finally` 块清理。响应式路径下，`AccessTraceState` 随 Reactor Context 传播，并由 `ReactorLogContextBridge` 在信号回调时临时恢复到 ThreadLocal / MDC，回调结束后立即还原，不会跨线程泄漏。
7. **分布式锁**：清理任务默认使用 Redis 分布式锁，无 Redis 时自动退化为单机模式。
8. **动态表名安全**：所有运行时拼接的表名均经过 `[a-zA-Z0-9_]` 正则校验，防止 SQL 注入。
9. **MQ 驱动配置**：`cy-common-mq` 当前不再隐式默认到 `LOCAL`（进程内队列）。生产环境必须显式配置 `cy.mq.driver=REDIS` 或 `KAFKA`；若日志链路依赖默认驱动但未配置 `cy.mq.driver`，应用会在启动期快速失败。
10. **死信处理**：当前默认支持自动回放；回放失败会按配置重新入队，达到上限后转入 parking lot Topic。运维侧应同时监控死信 Topic 与 parking lot Topic。
11. **响应式支持范围与前提**：
    - `Mono`：`@SysLog` 可采集 afterData / changeSummary / 成功 / 失败 / 取消 / 耗时；`@AccessLog` 可采集出参 / 耗时 / 成功 / 失败 / 取消。
    - `Flux`：多值流无法以单值记录 afterData，仅采集成功 / 失败 / 取消 / 耗时。
    - 若希望在 WebFlux / Reactor 回调中保持 `traceId` / 用户 / `requestMethod` / `requestUri` 连续性，需要入口层先建立 `InvocationContext`（如 `InvocationContextWebFilter`）。
    - 零侵入 `ApiLogAspect` 仍然只有 Servlet 实现；WebFlux 场景当前主要覆盖 `@SysLog` / `@AccessLog` / `LogService`。
