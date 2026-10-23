# cy-common-orm

统一 ORM 基础组件，为 Spring Boot 应用提供基于 `MyBatis-Flex` 的公共持久化能力，包括自动装配、基础实体字段、审计字段自动填充、ID 生成工具与 SQL 审计开关。

---

## 1. 组件能力总览

| 能力 | 说明 |
|------|------|
| **MyBatis-Flex 自动装配** | 通过 `AutoConfiguration.imports` 自动注册 `MyBatisFlexConfig`，无需手动 `@Import` |
| **基础实体字段** | 提供 `BaseEntity`，统一承载 `createTime / updateTime / createUser / updateUser / createName / updateName` |
| **审计字段自动填充** | 通过全局 `InsertListener / UpdateListener` 自动回填创建人与更新时间 |
| **上下文感知审计** | 基于 `InvocationScope` + `AuditUserResolver` 读取当前调用用户，支持匿名/系统/超级管理员场景 |
| **ID 生成工具** | 提供 `IdGeneratedUtil`，支持雪花 ID 与 UUID 生成 |
| **SQL 审计开关** | 支持通过配置打开 MyBatis-Flex SQL 审计与控制台 SQL 输出 |
| **Spring Boot 3 / Java 21** | 组件按 Spring Boot 3 与 Java 21 体系构建 |

### 1.1 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java** | 21+ | 组件按 Java 21 编译 |
| **Spring Boot** | 3.x | 使用 `@AutoConfiguration` 与 Boot 3 自动配置机制 |
| **MyBatis-Flex** | 1.9.x | 当前 ORM 基础能力的核心实现 |
| **cy-common-core** | 同版本 | 提供 `InvocationContext`、序列化器、上下文访问能力 |

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| ORM 核心 | MyBatis-Flex | 提供 `BaseMapper`、`ServiceImpl`、`QueryWrapper`、分页与监听器机制 |
| 数据源连接池 | HikariCP | 使用 Spring Boot 常规数据源体系 |
| 审计上下文 | InvocationContext | 统一承载用户、trace、span 等请求级上下文 |
| ID 生成 | Snowflake / UUID | 分别用于数值主键与业务侧随机标识 |

### 1.3 组件定位

```text
业务模块
└── 实体类继承 BaseEntity
    ├── MyBatis-Flex Mapper / ServiceImpl
    ├── 全局 Insert / Update Listener
    ├── InvocationContext / AuditUserResolver
    └── MyBatis-Flex SQL 审计与日志开关
```

> **定位说明**：`cy-common-orm` 是“ORM 公共基础层”，负责统一持久化约定，不承载业务 Repository 规则、监控面板或复杂数据访问框架封装。

---

## 2. 引入依赖与快速开始

### 2.1 Maven 依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-orm</artifactId>
</dependency>
```

组件通过 Spring Boot `AutoConfiguration.imports` 自动装配：

- `com.cy.orm.config.MyBatisFlexConfig`

无需手动 `@Import`。

### 2.2 最小配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/demo?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai
    username: root
    password: 123456
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-flex:
  audit_enable: false
  sql_print: false
```

### 2.3 基础用法

#### 实体继承 `BaseEntity`

```java
@Data
@EqualsAndHashCode(callSuper = true)
@Table("sys_user")
public class SysUser extends BaseEntity {

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    private String username;
    private String password;
}
```

#### Service 直接使用 MyBatis-Flex

```java
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> {

    public Boolean createUser(SysUser user) {
        return this.save(user);
    }
}
```

保存时会自动填充：

- `createUser`
- `createTime`
- `updateUser`
- `updateTime`

---

## 3. 功能说明

### 3.1 自动装配

组件自动注册 `MyBatisFlexConfig`，主要做三件事：

1. 注册 `BaseEntity` 的全局插入监听器
2. 注册 `BaseEntity` 的全局更新监听器
3. 根据配置打开 SQL 审计与 SQL 输出

### 3.2 基础实体 `BaseEntity`

`BaseEntity` 提供统一字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `createTime` | `Timestamp` | 创建时间 |
| `updateTime` | `Timestamp` | 最后更新时间 |
| `createUser` | `Long` | 创建人 ID |
| `updateUser` | `Long` | 最后更新人 ID |
| `createName` | `String` | 创建人名称，`@Column(ignore = true)` |
| `updateName` | `String` | 最后更新人名称，`@Column(ignore = true)` |

补充说明：

- 时间字段通过 `LongToTimestampDeserializer` / `TimestampToLongSerializer` 适配前后端时间戳传输
- `createName / updateName` 不入库，通常配合 `cy-common-userfill` 进行返回值补全

### 3.3 审计字段自动填充

#### 插入监听器

插入时自动处理：

- 创建人
- 创建时间
- 更新人
- 更新时间

规则：

1. 若存在调用上下文，则优先取当前用户 ID
2. 匿名用户返回 `0L`
3. 超级管理员统一映射为 `0L`
4. 若实体已显式设置 `createTime`，则保留原值

#### 更新监听器

更新时自动处理：

- 更新人
- 更新时间

规则：

1. 使用当前上下文用户 ID
2. 超级管理员映射为 `0L`
3. 始终刷新 `updateTime`

#### 异常处理

监听器不再吞异常：

- 会记录错误日志
- `RuntimeException` 直接透传
- 非运行时异常包装为 `IllegalStateException`

这意味着审计填充失败时，调用方能够明确感知失败，而不是静默成功。

### 3.4 上下文依赖

审计字段填充依赖调用上下文：

- Web 请求场景：通常由 `cy-common-starter-web` 初始化 `InvocationContext`
- 异步场景：应通过 `InvocationContextAccessor.wrap(...)` 传播上下文
- 系统任务场景：可使用系统上下文

若当前没有上下文，审计用户会降级为 `0L`。

### 3.5 ID 生成工具

组件提供 `IdGeneratedUtil`：

```java
Long id = IdGeneratedUtil.generateId();
String uuid = IdGeneratedUtil.generateUUID();
```

适用场景：

- `generateId()`：手动构造雪花主键
- `generateUUID()`：业务侧唯一名称、临时标识

### 3.6 SQL 审计与日志

组件支持两个开关：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `mybatis-flex.audit_enable` | `false` | 打开 MyBatis-Flex 审计功能 |
| `mybatis-flex.sql_print` | `false` | 打开控制台 SQL 输出，同时启用 SQL 审计 |

说明：

- `sql_print=true` 时会启用 `StdOutImpl`
- 审计消息收集器默认使用 `ConsoleMessageCollector`
- 该能力更偏开发/排障场景，生产环境应谨慎开启

---

## 4. 配置说明

### 4.1 `mybatis-flex.*` 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `mybatis-flex.audit_enable` | `boolean` | `false` | 是否打开 SQL 审计 |
| `mybatis-flex.sql_print` | `boolean` | `false` | 是否打印 SQL 到控制台 |

### 4.2 行为说明

组件内部还统一设置了两个 MyBatis-Flex 查询行为：

| 行为 | 值 | 说明 |
|------|----|------|
| `QueryColumnBehavior.setIgnoreFunction(...)` | `IGNORE_BLANK` | 忽略空白查询条件 |
| `QueryColumnBehavior.setSmartConvertInToEquals(true)` | `true` | 单值 `IN` 智能转 `=` |

---

## 5. 架构与目录结构

### 5.1 运行时结构

```text
MyBatisFlexConfig
├── 注册 EntityInsertListener
├── 注册 EntityUpdateListener
└── 配置 SQL 审计 / SQL 输出

EntityInsertListener / EntityUpdateListener
└── 通过 InvocationScope.current() + AuditUserResolver 获取审计用户

BaseEntity
└── 统一承载审计字段与前后端序列化规则

IdGeneratedUtil
└── 提供 Snowflake / UUID 生成
```

### 5.2 目录结构

```text
cy-common-orm/
├── src/main/java/com/cy/orm/
│   ├── config/
│   │   └── MyBatisFlexConfig.java
│   ├── core/domain/
│   │   └── BaseEntity.java
│   ├── listener/
│   │   ├── EntityInsertListener.java
│   │   └── EntityUpdateListener.java
│   └── utils/
│       └── IdGeneratedUtil.java
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── src/test/java/com/cy/orm/listener/
    └── EntityAuditListenerTest.java
```

---

## 6. 注意事项

1. **只有继承 `BaseEntity` 的实体才会享受全局审计字段填充**  
   非 `BaseEntity` 实体不会触发当前注册的监听器逻辑。

2. **审计字段依赖调用上下文**  
   如果请求没有正确初始化 `InvocationContext`，则用户会降级为 `0L`。

3. **异步任务需要手动传播上下文**  
   否则异步线程中的 ORM 填充逻辑无法拿到真实用户。

4. **`createName / updateName` 不入库**  
   它们只用于响应层展示，不能作为数据库查询条件。

5. **`sql_print` 更适合开发环境**  
   生产环境长期开启会带来日志噪音与潜在性能影响。

6. **组件不再包含 PageHelper**  
   当前组件已统一收敛到 MyBatis-Flex 体系，不再提供 PageHelper 分页配置。

---

## 7. 常见问题

### 7.1 为什么 `createUser / updateUser` 变成了 `0`？

常见原因：

- 当前请求没有初始化 `InvocationContext`
- 当前调用是匿名请求
- 当前调用用户是超级管理员，按约定映射为 `0L`
- 异步线程未传播上下文

### 7.2 为什么实体保存后没有自动填充审计字段？

请检查：

- 实体是否继承 `BaseEntity`
- 是否走的是 MyBatis-Flex 正常插入/更新链路
- 自动配置是否生效
- 是否在极早期就抛出了异常导致监听器未执行完毕

### 7.3 可以手动设置 `createTime` 吗？

可以。

插入监听器会保留已设置的 `createTime`，并用该值同步初始化 `updateTime`。

### 7.4 组件是否负责用户名回填？

不负责。

`cy-common-orm` 只负责 `createUser / updateUser` 等基础审计字段；`createName / updateName` 的补全通常由 `cy-common-userfill` 负责。

---

> **作者**：pcy  
> **最后更新**：2026-04-14
