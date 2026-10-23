# cy-common-core

核心基础组件，为 Spring Boot 应用提供**统一返回模型**、**分页查询条件**、**调用上下文**、**业务异常与错误消息解析**、**Spring 容器访问工具**、**Jackson 时间戳序列化工具**、**文档结构模型**和基础安全工具能力。

该组件只承载跨模块共享的最小核心抽象，不引入 `spring-boot-starter-web`、数据库、MQ、缓存等重型运行时能力，适合作为其他 `cy-commons` 组件和业务模块的基础依赖。

---

## 1. 组件能力总览

| 能力 | 说明 |
|------|------|
| **统一返回模型** | 提供 `R<T>` 作为接口返回结果模型，内置成功/失败状态码、提示信息、业务数据和时间戳 |
| **分页查询条件** | 提供 `Condition` 作为分页查询基类，统一 `pageable`、`page`、`size` 参数，并限制单页最大条数 |
| **调用上下文模型** | 提供 `InvocationContext`、`AuthUser`、`InvocationScope`、`InvocationContextAccessor`，承载用户、traceId、requestId、来源、客户端 IP 等请求级上下文 |
| **上下文跨线程传播** | 通过 `InvocationContextAccessor.wrap(...)` 捕获当前上下文和 MDC，保证异步任务中链路信息不丢失 |
| **审计用户解析** | `AuditUserResolver` 为 ORM 审计字段提供统一用户 ID 解析规则，未认证用户和超级管理员统一映射为 `0L` |
| **业务异常模型** | `CYException` 以错误码为中心表达业务异常，支持占位符参数和错误消息降级输出 |
| **错误消息解析** | `MessageResolver` 自动扫描 `classpath*:error-messages*.properties`，支持多个模块错误码文件合并加载 |
| **Spring 容器工具** | `SpringUtils` 提供静态获取 Bean、发布事件、读取虚拟线程状态等能力，适用于非 Spring 托管对象 |
| **Jackson 时间戳转换** | 提供 `LocalDateTime` / `Timestamp` 与毫秒时间戳互转的序列化、反序列化工具 |
| **文档结构模型** | 提供 `ParsedDocument`、`DocElement`、`ElementType`，作为文档解析、切片、知识库入库的通用数据结构 |
| **基础哈希工具** | `Md5Utils` 支持字符串、字节数组、输入流的 MD5 计算 |
| **Token 加密工具** | `TokenCrypto` 提供用户 ID 混淆、Token 生成、格式校验、用户缓存键生成等基础能力 |
| **自动配置接入** | 通过 Spring Boot `AutoConfiguration.imports` 自动注册 `CoreAutoConfiguration`，无需业务模块手动 `@Import` |
| **轻量依赖边界** | 仅依赖 Spring 基础上下文、SLF4J、Jackson、commons-lang3、Spring Boot autoconfigure 等基础库，不绑定 Web 容器 |

---

## 2. 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java** | 21+ | 与仓库统一 Java 版本保持一致 |
| **Spring Boot** | 3.x | 使用 `AutoConfiguration.imports` 方式注册自动配置 |
| **Spring Framework** | 6.x | 使用 `ApplicationContext`、`MessageSource`、资源扫描等基础能力 |
| **Jackson** | 2.x | 仅提供序列化/反序列化工具类，不强制注册全局 ObjectMapper |
| **SLF4J** | 2.x | 用于组件内部日志门面 |
| **Lombok** | provided | 用于模型类 getter/setter/builder 等代码生成 |
| **Swagger 注解**（可选） | 2.x Jakarta | 仅用于模型字段说明，可选依赖，不向运行时强绑定 |

---

## 3. 引入依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-core</artifactId>
</dependency>
```

组件继承 `cy-commons` 父工程的统一依赖管理，业务模块不需要在本模块重复声明版本。

---

## 4. 自动配置说明

组件通过以下文件接入 Spring Boot 自动配置：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

注册的自动配置类：

```text
com.cy.core.config.CoreAutoConfiguration
```

`CoreAutoConfiguration` 当前负责：

| Bean | 条件 | 说明 |
|------|------|------|
| `MessageResolver` | `@ConditionalOnMissingBean` | 自动扫描并加载 `error-messages*.properties` 错误消息文件 |
| `SpringUtils` | `@ComponentScan(basePackages = "com.cy.core")` | 作为 Spring 组件捕获 `ApplicationContext` |

> **注意**：`cy-common-core` 不提供 Web Filter、Controller Advice、数据库连接、MQ 消费者等运行时组件。Web 请求上下文创建、认证升级、异常统一拦截等能力应由上层 Web/Starter 组件负责。

---

## 5. 核心包结构

| 包名 | 主要类 | 说明 |
|------|--------|------|
| `com.cy.core.base` | `R`、`Condition` | 通用返回对象和分页查询条件 |
| `com.cy.core.config` | `CoreAutoConfiguration` | 核心模块自动配置入口 |
| `com.cy.core.constants` | `Constants` | 系统前缀、缓存键、超级管理员、通用符号等常量 |
| `com.cy.core.context` | `InvocationContext`、`AuthUser`、`InvocationScope`、`InvocationContextAccessor`、`AuditUserResolver` | 调用上下文、用户信息、上下文生命周期和审计用户解析 |
| `com.cy.core.exception` | `CYException`、`MessageResolver` | 业务异常模型和错误消息解析 |
| `com.cy.core.model` | `ParsedDocument`、`DocElement`、`ElementType` | 文档解析后的结构化模型 |
| `com.cy.core.serializer` | `LocalDateTimeToLongSerializer`、`LongToLocalDateTimeDeserializer`、`TimestampToLongSerializer`、`LongToTimestampDeserializer` | Jackson 时间戳转换工具 |
| `com.cy.core.utils` | `SpringUtils`、`Md5Utils`、`TokenCrypto` | Spring 容器访问、哈希、Token 工具 |

---

## 6. 使用场景与示例

### 6.1 统一接口返回 `R<T>`

**场景**：Controller 或 Facade 层需要返回统一响应结构。

```java
@RestController
@RequestMapping("/system/users")
public class UserController {

    @GetMapping("/{id}")
    public R<UserVO> getUser(@PathVariable Long id) {
        UserVO user = userService.getById(id);
        return R.ok(user);
    }

    @DeleteMapping("/{id}")
    public R<Void> deleteUser(@PathVariable Long id) {
        userService.deleteById(id);
        return R.ok();
    }
}
```

失败结果：

```java
return R.error(90003, "参数错误");
```

默认约定：

| 字段 | 说明 |
|------|------|
| `status` | 状态码，默认成功码为 `1000` |
| `message` | 提示信息，默认成功提示为 `操作成功` |
| `data` | 业务数据 |
| `timestamp` | 响应对象创建时间戳 |

### 6.2 分页条件 `Condition`

**场景**：列表查询 DTO 继承统一分页字段，避免各模块重复定义 `page`、`size`。

```java
public class UserQuery extends Condition {

    private String username;

    private String status;
}
```

分页规则：

| 字段 | 默认值 | 规则 |
|------|--------|------|
| `pageable` | `true` | 是否分页 |
| `page` | `1` | 小于 `1` 时自动修正为 `1` |
| `size` | `10` | 小于 `1` 时自动修正为 `1`，最大不超过 `200` |

### 6.3 业务异常 `CYException`

**场景**：业务层通过错误码抛出异常，由上层统一异常处理器转换为接口响应。

```java
public void createUser(CreateUserCommand command) {
    if (command.getUsername() == null) {
        throw new CYException(90003);
    }
    if (userRepository.existsByUsername(command.getUsername())) {
        throw new CYException(10001, command.getUsername());
    }
}
```

错误消息文件示例：

```properties
90003=参数错误
10001=用户名 {0} 已存在
```

`MessageResolver` 会扫描所有 `error-messages*.properties` 文件：

```text
classpath*:error-messages*.properties
```

建议各模块按模块名拆分错误码文件：

```text
error-messages-core.properties
error-messages-user.properties
error-messages-order.properties
```

加载规则：

| 规则 | 说明 |
|------|------|
| **统一扫描** | 自动扫描 classpath 下所有 `error-messages*.properties` |
| **UTF-8 编码** | 默认按 UTF-8 读取，支持中文消息 |
| **占位符格式化** | 使用 `MessageFormat` 语义，支持 `{0}`、`{1}` 等参数 |
| **降级输出** | 未找到错误码时返回 `Unknown error [错误码]` |

### 6.4 调用上下文 `InvocationContext`

**场景**：在业务链路中统一读取当前用户、traceId、requestId、来源和客户端 IP。

```java
public void updateProfile(UpdateProfileCommand command) {
    InvocationContext context = InvocationContextAccessor.currentRequired();

    Long userId = context.getUserId();
    String traceId = context.getTraceId();

    profileRepository.update(userId, command, traceId);
}
```

上下文字段：

| 字段 | 说明 |
|------|------|
| `authUser` | 当前认证用户，未认证时为 `null` |
| `traceId` | 全链路追踪 ID |
| `requestId` | 当前服务节点请求 ID / Span ID |
| `parentSpanId` | 调用方 requestId，用于构建调用链路树 |
| `source` | 请求来源：`external`、`gateway`、`internal`、`system` |
| `clientIp` | 客户端 IP |
| `authenticated` | 是否已认证 |
| `gatewayForwarded` | 是否由网关转发 |

系统任务可使用系统上下文：

```java
InvocationContext context = InvocationContext.system();

try (InvocationScope ignored = InvocationScope.open(context)) {
    jobService.execute();
}
```

### 6.5 上下文作用域与异步传播

**场景**：异步任务、线程池、事件回调中需要保留调用上下文和 MDC。

```java
Runnable task = InvocationContextAccessor.wrap(() -> {
    Long userId = InvocationContextAccessor.currentUserId();
    auditService.record(userId);
});

executor.execute(task);
```

显式绑定上下文：

```java
InvocationContext systemContext = InvocationContext.system();

InvocationContextAccessor.run(systemContext, () -> {
    syncService.syncData();
});
```

设计约束：

| 约束 | 说明 |
|------|------|
| **栈式作用域** | `InvocationScope` 使用 ThreadLocal 栈，支持嵌套上下文 |
| **自动清理** | 推荐使用 try-with-resources，作用域关闭后自动弹出上下文 |
| **Service 不显式传参** | 业务 Service 不应把 `InvocationContext` 作为方法参数层层传递 |
| **跨线程显式传播** | 新线程不会自动继承 ThreadLocal，必须使用 `wrap(...)` 或任务装饰器 |

### 6.6 审计用户解析

**场景**：ORM 自动填充 `createUser` / `updateUser` 时，需要统一处理未登录用户和超级管理员。

```java
InvocationContext context = InvocationContextAccessor.current();
Long auditUserId = AuditUserResolver.resolveUserId(context);
```

解析规则：

| 场景 | 返回值 |
|------|--------|
| 上下文为空 | `0L` |
| 用户 ID 为空 | `0L` |
| 超级管理员 | `0L` |
| 普通用户 | 实际用户 ID |

### 6.7 Spring 容器工具 `SpringUtils`

**场景**：非 Spring 托管对象、静态工具、异常对象中需要访问 Spring Bean 或发布事件。

```java
if (SpringUtils.isReady()) {
    UserService userService = SpringUtils.getBean(UserService.class);
    userService.refreshCache();
}
```

发布事件：

```java
SpringUtils.publishEvent(new UserUpdatedEvent(userId));
```

使用建议：

| 建议 | 说明 |
|------|------|
| **优先构造器注入** | Spring 管理的 Bean 内部优先使用构造器注入，不要滥用静态取 Bean |
| **启动后调用** | 容器未初始化时调用会抛出 `IllegalStateException` |
| **边界场景使用** | 适合异常对象、SPI、反射创建对象等无法注入 Bean 的场景 |

### 6.8 Jackson 时间戳序列化

**场景**：接口层需要将 `LocalDateTime` 或 `Timestamp` 与毫秒时间戳互转。

```java
public class UserVO {

    @JsonSerialize(using = LocalDateTimeToLongSerializer.class)
    @JsonDeserialize(using = LongToLocalDateTimeDeserializer.class)
    private LocalDateTime createTime;

    @JsonSerialize(using = TimestampToLongSerializer.class)
    @JsonDeserialize(using = LongToTimestampDeserializer.class)
    private Timestamp updateTime;
}
```

转换规则：

| 类型 | 序列化 | 反序列化 |
|------|--------|----------|
| `LocalDateTime` | 毫秒时间戳 | 毫秒时间戳转本地时区 `LocalDateTime` |
| `Timestamp` | 毫秒时间戳 | 毫秒时间戳转 `Timestamp` |

> **注意**：本组件只提供序列化器类，不自动修改全局 `ObjectMapper`。需要全局生效时，应由上层 Web/Starter 组件统一注册。

### 6.9 文档结构模型

**场景**：文档解析、知识库切片、内容结构化入库时统一表达解析结果。

```java
ParsedDocument document = ParsedDocument.builder()
        .originalFilename("demo.pdf")
        .contentType("application/pdf")
        .text("全文内容")
        .build();

DocElement title = DocElement.builder()
        .type(ElementType.TITLE)
        .content("第一章 总览")
        .level(1)
        .index(0)
        .headingPath("第一章 总览")
        .build();

document.addElement(title);
```

核心模型：

| 模型 | 说明 |
|------|------|
| `ParsedDocument` | 解析后的完整文档，包含元数据、全文、结构化元素、目录元素、总页数 |
| `DocElement` | 文档中的单个结构化元素，包含类型、内容、层级、页码、偏移量、标题路径、元数据 |
| `ElementType` | 元素类型枚举：标题、段落、表格、列表、图片、代码块、目录、其他 |

### 6.10 哈希与 Token 工具

MD5 示例：

```java
String digest = Md5Utils.md5("hello");
```

Token 示例：

```java
String token = TokenCrypto.generateToken(10001L);
boolean valid = TokenCrypto.validateTokenFormat(token);
Long userId = TokenCrypto.parseUserIdFromToken(token);
String userCacheKey = TokenCrypto.generateUserCacheKey(userId);
```

生产环境建议显式配置密钥：

| 配置项 | 说明 |
|--------|------|
| `CY_TOKEN_XOR_KEY` | 用户 ID XOR 混淆密钥，至少 8 字节 ASCII |
| `CY_TOKEN_SIGN_SECRET` | Token 签名密钥 |
| `CY_TOKEN_USER_KEY_SALT` | 用户缓存键盐值 |
| `cy.token.xor-key` | XOR 密钥的系统属性形式 |

> **注意**：`TokenCrypto` 适合轻量内部 Token 和缓存键场景，不等同于标准 OAuth2 / JWT 认证体系。对外开放认证场景应由认证服务统一设计签发、续期、吊销和密钥轮换策略。

---

## 7. 架构边界

`cy-common-core` 是所有公共组件的底座，应保持轻量、稳定、低耦合。

| 边界 | 说明 |
|------|------|
| **不引入 Web Starter** | 不依赖 `spring-boot-starter-web` 或 `spring-boot-starter-webflux`，避免基础组件强行拉起 Web 栈 |
| **不绑定数据层** | 不依赖数据源、ORM、事务、MyBatis、JPA 等能力 |
| **不绑定中间件** | 不依赖 Redis、MQ、Nacos、MinIO、Milvus 等外部基础设施 |
| **不承载业务逻辑** | 只提供跨模块通用模型、工具和抽象，不放具体业务流程 |
| **不覆盖上层策略** | 异常响应格式、Web 上下文创建、全局 ObjectMapper 注册等由上层 Starter 或业务服务决定 |

推荐依赖方向：

```text
业务服务
  └── cy-common-starter-* / cy-common-log / cy-common-orm / ...
        └── cy-common-core
```

禁止反向依赖：

```text
cy-common-core
  └── Web / ORM / MQ / Redis / 业务模块
```

---

## 8. 生产使用建议

| 建议 | 说明 |
|------|------|
| **错误码分段管理** | 每个模块维护自己的 `error-messages-xxx.properties`，避免错误码冲突 |
| **上下文入口统一创建** | Servlet/WebFlux/消息消费/定时任务等入口统一创建 `InvocationContext`，业务层只读取不创建 |
| **异步任务必须传播上下文** | 线程池、CompletableFuture、事件回调使用 `InvocationContextAccessor.wrap(...)` |
| **核心模块保持轻依赖** | 新增功能时优先判断是否属于 core；涉及 Web、ORM、MQ、缓存的能力应放到对应组件 |
| **Token 密钥外部化** | 生产环境不要依赖默认密钥，应通过环境变量或系统属性显式配置 |
| **SpringUtils 谨慎使用** | 优先使用依赖注入，只有在无法注入的边界场景使用静态容器访问 |
| **序列化策略上移** | core 只提供序列化工具，全局 JSON 策略应由 Web Starter 或业务服务统一配置 |

---

## 9. 发布校验

作为基础组件，`cy-common-core` 的改动应至少通过 `cy-commons` 聚合构建：

```bash
mvn -f cy-commons/pom.xml clean verify
```

涉及依赖边界或公开 API 调整时，应同步验证核心下游模块：

```bash
mvn -f cy-orrery-core/orrery-sdk/pom.xml -DskipTests clean compile
mvn -f cy-orrery-core/orrery-skill-runtime/pom.xml -DskipTests clean compile
mvn -f cy-orrery-services/orrery-registry-center/pom.xml -DskipTests clean compile
```

发布前重点确认：

| 检查项 | 标准 |
|--------|------|
| **依赖边界** | 不新增 Web、ORM、MQ、Redis 等重型依赖 |
| **自动配置** | `AutoConfiguration.imports` 正确声明，启动时可自动加载 |
| **错误消息** | `error-messages*.properties` 使用 UTF-8，错误码无明显冲突 |
| **二方兼容** | 公共类、方法、字段变更需评估下游模块编译影响 |
| **构建门禁** | `clean verify` 失败时不允许发版 |
