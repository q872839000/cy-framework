# cy-common-cache

统一缓存组件，为 Spring Boot 应用提供基于 `Redisson + Caffeine` 的二级缓存、增强 Spring Cache、自定义缓存注解、`RedisUtils` / `CacheUtils` 工具类、分布式锁与限流能力。

---

## 1. 组件能力总览

| 能力 | 说明 |
|------|------|
| **二级缓存** | `FlexSpringCacheManager` + `CaffeineCacheDecorator` 实现本地 Caffeine + Redis 两级缓存，读取优先命中本地缓存 |
| **动态 TTL / MaxIdle / MaxSize** | Spring Cache 的 `cacheNames` 支持 `name#ttl#maxIdleTime#maxSize` 形式动态声明缓存策略 |
| **自定义缓存注解** | 提供 `@CacheResult`、`@CachePut`、`@CacheEvict`、`@CacheMapPut`、`@CacheMapEvict`，支持 SpEL |
| **丰富的 Redis 操作** | `RedisUtils` 封装 String、List、Set、Hash、AtomicLong、Key 模式匹配、监听器、限流、跨服务 shared key 等常用能力 |
| **Spring Cache 工具化操作** | `CacheUtils` 基于 `CacheManager` 提供 `get`、`put`、`evict`、`clear`、`keys` 等便捷 API |
| **服务私有与共享 Key 前缀** | `cy.cache.redis.keyPrefix` 隔离默认 Redis 操作，`sharedKeyPrefixes` / `RedisSharedKeyPrefixProvider` 声明跨服务共享 key |
| **分布式能力** | 复用 Redisson 客户端，同时集成 Lock4j 注解式分布式锁与令牌桶限流 |
| **自动装配** | 通过 `AutoConfiguration.imports` 自动注册 `RedisConfig` 与 `CacheConfig`，无需手动 `@Import` |
| **虚拟线程适配** | 检测到 Spring Boot 虚拟线程环境时，自动为 Redisson 配置虚拟线程执行器 |

### 1.1 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java** | 21+ | 组件按 Java 21 编译，并支持虚拟线程环境 |
| **Spring Boot** | 3.x | 使用 `@AutoConfiguration`、Boot 3 的线程环境检测等能力 |
| **Redis** | 5.0+（推荐 6.0+） | Redisson 作为底层客户端；`setAndKeepTTL` 在 Redis 6+ 原生支持，5.x 有兼容兜底 |
| **cy-common-core** | 同版本 | 提供 `SpringUtils` 等基础能力 |
| **redisson-spring-boot-starter** | 与项目依赖保持一致 | 提供 Redisson 客户端与 Spring Boot 集成 |
| **caffeine** | 与项目依赖保持一致 | 作为一级本地缓存 |
| **lock4j-redisson-spring-boot-starter** | 与项目依赖保持一致 | 提供注解式分布式锁 |

### 1.2 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| 分布式缓存 | Redisson | Redis 客户端，承载缓存、锁、限流、Map/Set/List 等能力 |
| 本地缓存 | Caffeine | 作为一级缓存，降低热点读请求的 Redis 往返 |
| 分布式锁 | Lock4j | 注解式加锁，底层使用 Redisson |
| 序列化 | Jackson | Key 使用 `StringCodec`，Value 使用 JSON，并保留类型信息 |

### 1.3 缓存架构

```text
业务代码
├── Spring Cache 注解 / CacheUtils
│   └── FlexSpringCacheManager
│       └── Caffeine（一级本地缓存，默认 30 秒）
│           └── Redis（二级分布式缓存）
└── 自定义缓存注解 / RedisUtils / Lock4j
    └── Redis（直接访问，不经过一级本地缓存）
```

> **关键区别**：只有 Spring Cache 抽象层（如 `@Cacheable`、`CacheUtils`）会走 `Caffeine -> Redis` 两级路径；自定义注解和 `RedisUtils` 直接操作 Redis。

---

## 2. 引入依赖与快速开始

### 2.1 Maven 依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-cache</artifactId>
</dependency>
```

组件通过 Spring Boot `AutoConfiguration.imports` 自动注册：

- `com.cy.redis.config.RedisConfig`
- `com.cy.redis.config.CacheConfig`

无需手动 `@Import`。

### 2.2 最小配置

组件复用 Spring Boot 标准 Redis 配置，并扩展 `cy.cache.redis.*` 属性：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      timeout: 10s

cy:
  cache:
    redis:
      keyPrefix: myapp
      sharedKeyPrefixes:
        - system:global:
      threads: 16
      nettyThreads: 32
      singleServerConfig:
        clientName: myapp
        database: 0
        connectionPoolSize: 64
        connectionMinimumIdleSize: 24
        idleConnectionTimeout: 10000
        timeout: 3000
        subscriptionConnectionPoolSize: 50

cy:
  cache:
    local:
      enabled: true
      expire-after-write: 30s
      initial-capacity: 100
      maximum-size: 1000
```

> **说明**：`singleServerConfig` 与 `clusterServersConfig` 二选一即可。
>
> **Key 前缀语义**：`cy.cache.redis.keyPrefix` 表示服务私有 Redis 命名空间。默认 `RedisUtils`、自定义缓存注解、Spring Cache Redis 路径都会受该前缀影响；跨服务共享数据应通过 `sharedKeyPrefixes` 或 `RedisSharedKeyPrefixProvider` 声明共享前缀，未注册共享前缀时可使用 `RedisUtils` 的 shared 方法兜底。
>
> **补充**：`cy.cache.local.*` 用于控制 Spring Cache 路径上的本地 Caffeine 一级缓存。

### 2.3 基础使用

```java
@Cacheable(cacheNames = "user#30m", key = "#id")
public User getById(Long id) {
    return userMapper.selectById(id);
}

RedisUtils.setCacheObject("user:1", user, Duration.ofMinutes(30));
User user = RedisUtils.getCacheObject("user:1");
```

---

## 3. 功能说明

### 3.1 Spring Cache 集成

组件内置 `FlexSpringCacheManager`，支持通过 `cacheNames` 动态声明缓存参数，格式如下：

```text
name#ttl#maxIdleTime#maxSize
```

| 片段 | 是否必填 | 说明 | 示例 |
|------|----------|------|------|
| `name` | 是 | 缓存名称 | `user` |
| `ttl` | 否 | 过期时间 | `30m` |
| `maxIdleTime` | 否 | 最大空闲时间 | `10m` |
| `maxSize` | 否 | 最大条目数 | `1000` |

支持的时间格式由 Spring Boot `DurationStyle` 解析，常用写法包括 `30s`、`5m`、`1h`、`1d`。

```java
@Cacheable(cacheNames = "user", key = "#id")
public User getUser(Long id) {
    return userMapper.selectById(id);
}

@Cacheable(cacheNames = "user#30m", key = "#id")
public User getUserWithTtl(Long id) {
    return userMapper.selectById(id);
}

@Cacheable(cacheNames = "user#1h#30m#1000", key = "#id")
public User getHotUser(Long id) {
    return userMapper.selectById(id);
}
```

读取路径为：

1. 先查本地 `Caffeine`
2. 本地未命中再查 Redis
3. 命中结果回填本地缓存

写入与删除仍以 Redis 为主，本地缓存依赖短 TTL 和按 key 失效来降低不一致窗口。

### 3.2 自定义缓存注解

除 Spring 标准注解外，组件还提供一组直接面向 Redis 的自定义注解：

| 注解 | 作用 | 关键属性 |
|------|------|----------|
| `@CacheResult` | 先查缓存，未命中时执行方法并写回 | `keyPrefix`、`key`、`expire`、`timeUnit`、`condition`、`cacheNull` |
| `@CachePut` | 执行方法后强制刷新缓存 | `keyPrefix`、`key`、`expire`、`timeUnit`、`condition`、`cacheNull` |
| `@CacheEvict` | 删除指定缓存或按前缀批量删除 | `keyPrefix`、`keys`、`allEntries`、`condition`、`beforeInvocation` |
| `@CacheMapPut` | 更新 Redis Hash 中的 field | `mapKey`、`hashKey`、`value`、`condition` |
| `@CacheMapEvict` | 删除 Redis Hash 中的 field 或整个 Hash | `mapKey`、`hashKey`、`allEntries`、`condition` |

> **注意**：这组注解底层直接调用 `RedisUtils`，**不经过一级 Caffeine 本地缓存**，更适合做 Redis Key / Hash 的精确控制。

#### `@CacheResult` - 查询缓存

```java
@CacheResult(
    keyPrefix = "user",
    key = "#{#id}",
    expire = 30,
    timeUnit = TimeUnit.MINUTES,
    condition = "#id != null",
    cacheNull = false
)
public User getById(Long id) {
    return userMapper.selectById(id);
}
```

说明：

- `expire <= 0` 时表示永不过期
- `condition` 在方法执行前判断，因此这里通常使用参数，不建议依赖 `#result`

#### `@CachePut` - 更新缓存

```java
@CachePut(
    keyPrefix = "user",
    key = "#{#user.id}",
    condition = "#result != null"
)
public User updateUser(User user) {
    userMapper.updateById(user);
    return user;
}
```

说明：

- 先执行方法，再根据 `condition` 判断是否写缓存
- `condition` 中可以使用 `#result`

#### `@CacheEvict` - 清除缓存

```java
@CacheEvict(
    keyPrefix = "user",
    keys = {"#{#id}"},
    beforeInvocation = false
)
public void deleteUser(Long id) {
    userMapper.deleteById(id);
}

@CacheEvict(keyPrefix = "user", allEntries = true)
public void clearUserCache() {
}
```

说明：

- `beforeInvocation = true` 时会在方法执行前删除缓存
- `allEntries = true` 时会按 `keyPrefix + "*"` 批量删除
- 若 `keyPrefix` 为空且 `allEntries = true`，删除范围会非常大，需谨慎使用

#### `@CacheMapPut` / `@CacheMapEvict` - Hash 结构操作

```java
@CacheMapPut(
    mapKey = "sys:user:names",
    hashKey = "#{#user.id}",
    value = "#{#user.nickname}"
)
public User updateNickname(User user) {
    return user;
}

@CacheMapEvict(
    mapKey = "sys:user:names",
    hashKey = "#{#id}"
)
public void deleteUserName(Long id) {
}
```

说明：

- 适合维护 `id -> nickname`、`code -> name` 等 Hash 索引
- 当前实现会将 `value` 表达式解析结果以字符串形式写入 Hash

### 3.3 SpEL 表达式规则

自定义注解支持参数、返回值与字符串模板两种场景：

| 变量 | 说明 | 示例 |
|------|------|------|
| `#参数名` | 按参数名引用 | `#{#id}` |
| `#p0`, `#p1` | 按参数下标引用 | `#{#p0}` |
| `#result` | 方法返回值 | `#{#result.id}` |

使用规则：

- `key`、`hashKey`、`value` 这类“模板字符串”字段使用 `#{...}` 语法
- `condition` 使用原生 SpEL 表达式，例如 `#id != null`、`#result != null`
- 字符串模板支持混写，例如 `user:#{#id}` 会解析为 `user:123`

### 3.4 工具类

#### `RedisUtils` - 直接操作 Redis

```java
// 对象
RedisUtils.setCacheObject("user:1", user);
RedisUtils.setCacheObject("user:1", user, Duration.ofMinutes(30));
User cached = RedisUtils.getCacheObject("user:1");
RedisUtils.deleteObject("user:1");
RedisUtils.expire("user:1", 60);

// List / Set
RedisUtils.setCacheList("user:list", users);
RedisUtils.addCacheSet("roles", "admin");

// Hash
RedisUtils.setCacheMapValue("sys:user:names", "1", "张三");
String nickname = RedisUtils.getCacheMapValue("sys:user:names", "1");
RedisUtils.delCacheMapValue("sys:user:names", "1");

// 原子计数
RedisUtils.setAtomicValue("counter", 0);
long current = RedisUtils.incrAtomicValue("counter");

// 限流
long permits = RedisUtils.rateLimiter("api:limit", RateType.OVERALL, 100, 60);

// Key 模式操作
Collection<String> keys = RedisUtils.keys("user:*");
RedisUtils.deleteKeys("user:*");
boolean exists = RedisUtils.hasKey("user:1");
```

补充说明：

- 提供 `setObjectIfAbsent` / `setObjectIfExists` 条件写入
- 提供 `getCacheListRange`、`getCacheMapKeySet`、`getMultiCacheMapValue`、`delMultiCacheMapValue` 等批量辅助方法
- 提供对象 / List / Set / Map 监听器，但依赖 Redis 开启 `notify-keyspace-events`
- 默认方法使用服务私有命名空间；命中 `sharedKeyPrefixes` 或 `RedisSharedKeyPrefixProvider` 声明前缀的 key 会保持原始 key，不叠加服务私有 `keyPrefix`

组件可通过注册 `RedisSharedKeyPrefixProvider` 声明自己的共享 key 前缀：

```java
@Bean
public RedisSharedKeyPrefixProvider authRedisSharedKeyPrefixProvider() {
    return () -> List.of(
        "system:auth:token:",
        "system:auth:user:token:"
    );
}
```

#### `CacheUtils` - 操作 Spring Cache

```java
Set<Object> keys = CacheUtils.keys("user");
User user = CacheUtils.get("user", 1L);
CacheUtils.put("user", 1L, user);
CacheUtils.evict("user", 1L);
CacheUtils.clear("user");
```

适用场景：

- 你已经在使用 Spring Cache，并希望通过 `CacheManager` 统一操作缓存
- 你希望复用组件提供的二级缓存路径（本地 Caffeine + Redis）

### 3.5 分布式锁与限流

组件依赖中已集成 Lock4j（底层 Redisson 实现），可直接在业务中使用：

```java
@Lock4j(keys = {"#id"}, expire = 30000, acquireTimeout = 3000)
public void doSomething(Long id) {
}
```

如需轻量级限流，可直接使用 `RedisUtils.rateLimiter(...)`。

---

## 4. 配置说明

组件配置由两部分组成：

- Spring Boot 标准 Redis 连接配置：`spring.data.redis.*`
- 组件扩展 Redisson 配置：`cy.cache.redis.*`

### 4.1 Redis 连接配置

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `spring.data.redis.host` | String | 单机 Redis 主机 |
| `spring.data.redis.port` | int | 单机 Redis 端口 |
| `spring.data.redis.password` | String | Redis 密码，可为空 |
| `spring.data.redis.database` | int | 单机模式数据库编号 |
| `spring.data.redis.timeout` | Duration | 连接 / 命令超时 |
| `spring.data.redis.cluster.nodes` | List | 集群模式节点列表 |

### 4.2 `cy.cache.redis.*` 扩展配置

| 配置项 | 类型 | 说明 |
|--------|------|------|
| `cy.cache.redis.keyPrefix` | String | 服务私有 Key 前缀，建议多应用共享 Redis 时显式配置 |
| `cy.cache.redis.sharedKeyPrefixes` | List<String> | 跨服务共享 Key 前缀，命中后保持原始 key，不叠加服务私有 `keyPrefix`；组件内置共享前缀建议通过 `RedisSharedKeyPrefixProvider` 注册 |
| `cy.cache.redis.threads` | int | Redisson 工作线程数，`> 0` 时才会覆盖默认值 |
| `cy.cache.redis.nettyThreads` | int | Redisson Netty 线程数，`> 0` 时才会覆盖默认值 |
| `cy.cache.redis.singleServerConfig.clientName` | String | 单机模式客户端名 |
| `cy.cache.redis.singleServerConfig.database` | int | 单机模式数据库编号 |
| `cy.cache.redis.singleServerConfig.connectionPoolSize` | int | 单机模式连接池大小 |
| `cy.cache.redis.singleServerConfig.connectionMinimumIdleSize` | int | 单机模式最小空闲连接数 |
| `cy.cache.redis.singleServerConfig.idleConnectionTimeout` | int | 单机模式连接空闲超时，毫秒 |
| `cy.cache.redis.singleServerConfig.timeout` | int | 单机模式命令超时，毫秒 |
| `cy.cache.redis.singleServerConfig.subscriptionConnectionPoolSize` | int | 单机模式发布订阅连接池大小 |
| `cy.cache.redis.clusterServersConfig.clientName` | String | 集群模式客户端名 |
| `cy.cache.redis.clusterServersConfig.masterConnectionMinimumIdleSize` | int | 集群模式 master 最小空闲连接数 |
| `cy.cache.redis.clusterServersConfig.masterConnectionPoolSize` | int | 集群模式 master 连接池大小 |
| `cy.cache.redis.clusterServersConfig.slaveConnectionMinimumIdleSize` | int | 集群模式 slave 最小空闲连接数 |
| `cy.cache.redis.clusterServersConfig.slaveConnectionPoolSize` | int | 集群模式 slave 连接池大小 |
| `cy.cache.redis.clusterServersConfig.idleConnectionTimeout` | int | 集群模式连接空闲超时，毫秒 |
| `cy.cache.redis.clusterServersConfig.timeout` | int | 集群模式命令超时，毫秒 |
| `cy.cache.redis.clusterServersConfig.subscriptionConnectionPoolSize` | int | 集群模式发布订阅连接池大小 |
| `cy.cache.redis.clusterServersConfig.readMode` | Enum | 读模式，如 `SLAVE` / `MASTER` / `MASTER_SLAVE` |
| `cy.cache.redis.clusterServersConfig.subscriptionMode` | Enum | 订阅模式，如 `MASTER` / `SLAVE` |

### 4.3 本地缓存配置

组件还提供 `cy.cache.local.*` 用于控制一级本地缓存：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `cy.cache.local.enabled` | boolean | `true` | 是否启用本地一级缓存 |
| `cy.cache.local.expire-after-write` | Duration | `30s` | 本地缓存写入后过期时间 |
| `cy.cache.local.initial-capacity` | int | `100` | 本地缓存初始容量 |
| `cy.cache.local.maximum-size` | long | `1000` | 本地缓存最大条目数 |

适用范围：

- 仅作用于 `FlexSpringCacheManager` 管理的 Spring Cache 路径
- 不影响 `RedisUtils` 直连 Redis
- 关闭后会退化为纯 Redis 缓存路径

### 4.4 配置示例

#### 单机模式

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: your_password
      database: 0
      timeout: 10s

redisson:
  keyPrefix: myapp
  threads: 16
  nettyThreads: 32
  singleServerConfig:
    clientName: myapp
    database: 0
    connectionPoolSize: 64
    connectionMinimumIdleSize: 24
    idleConnectionTimeout: 10000
    timeout: 3000
    subscriptionConnectionPoolSize: 50

cy:
  cache:
    local:
      enabled: true
      expire-after-write: 30s
      initial-capacity: 100
      maximum-size: 1000
```

#### 集群模式

```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - 192.168.0.100:6379
          - 192.168.0.101:6379
          - 192.168.0.102:6379
      password: your_password
      timeout: 10s

redisson:
  keyPrefix: myapp
  threads: 16
  nettyThreads: 32
  clusterServersConfig:
    clientName: myapp
    masterConnectionMinimumIdleSize: 32
    masterConnectionPoolSize: 64
    slaveConnectionMinimumIdleSize: 32
    slaveConnectionPoolSize: 64
    idleConnectionTimeout: 10000
    timeout: 3000
    subscriptionConnectionPoolSize: 50
    readMode: SLAVE
    subscriptionMode: MASTER

cy:
  cache:
    local:
      enabled: true
      expire-after-write: 30s
      initial-capacity: 100
      maximum-size: 1000
```

---

## 5. 架构与目录结构

### 5.1 运行时结构

```text
RedisConfig
└── 配置序列化、Key 前缀、线程池、单机/集群模式

CacheConfig
├── Caffeine Bean（默认 30 秒 TTL、初始容量 100、最大 1000）
├── FlexSpringCacheManager
└── CacheAspect

FlexSpringCacheManager
└── CaffeineCacheDecorator
    └── RedissonCache / RMap / RMapCache
```

### 5.2 目录结构

```text
cy-common-cache/
├── src/main/java/com/cy/redis/
│   ├── annotation/
│   │   ├── CacheResult.java
│   │   ├── CachePut.java
│   │   ├── CacheEvict.java
│   │   ├── CacheMapPut.java
│   │   └── CacheMapEvict.java
│   ├── aspect/
│   │   └── CacheAspect.java
│   ├── config/
│   │   ├── RedisConfig.java
│   │   ├── CacheConfig.java
│   │   └── properties/
│   │       └── RedissonProperties.java
│   ├── handler/
│   │   └── KeyPrefixHandler.java
│   ├── manager/
│   │   ├── FlexSpringCacheManager.java
│   │   └── CaffeineCacheDecorator.java
│   └── utils/
│       ├── RedisUtils.java
│       └── CacheUtils.java
└── src/main/resources/
    └── META-INF/spring/
        └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

---

## 6. 注意事项

1. **一级缓存只覆盖 Spring Cache 路径**  
   `@Cacheable`、Spring `CacheManager`、`CacheUtils` 会走 `Caffeine -> Redis` 两级缓存；`@CacheResult`、`@CachePut`、`@CacheEvict`、`RedisUtils` 直接访问 Redis。

2. **存在短暂一致性窗口**  
   本地 Caffeine 默认 30 秒过期，跨节点不会做实时广播失效。对强一致要求高的场景，优先直接使用 `RedisUtils`，或在更新后同步驱逐对应 Spring Cache。

3. **序列化包含类型信息**  
   Value 使用 Jackson JSON 且启用了默认类型信息，业务实体尽量不要使用 `final` 修饰，避免反序列化受限。

4. **Key 前缀表示服务私有命名空间**  
   配置 `cy.cache.redis.keyPrefix` 后，默认 Redis 操作会自动补服务私有前缀。排查线上 Key 时，需要按“服务前缀 + 原始 key”理解真实存储结构。

5. **跨服务共享数据必须声明共享前缀**  
   登录态、全局协同状态等需要多个服务共同读写的数据，应通过 `RedisSharedKeyPrefixProvider` 或 `cy.cache.redis.sharedKeyPrefixes` 声明共享前缀。命中共享前缀的普通 `RedisUtils` 调用会保持原始 key，不叠加服务私有 `keyPrefix`。

6. **监听器依赖 Redis 事件配置**  
   `addObjectListener`、`addListListener`、`addSetListener`、`addMapListener` 等能力依赖 Redis 开启 `notify-keyspace-events`。

7. **虚拟线程为自动适配**  
   当 Spring Boot 检测到虚拟线程环境时，组件会为 Redisson 配置 `VirtualThreadTaskExecutor`；未开启时保持默认线程模型。

---

## 7. 常见问题

### 7.1 缓存不生效？

- 确认目标方法是 `public`
- 确认是通过 Spring 代理调用，而不是 `this.xxx()`
- 检查 `cacheNames` 格式是否正确，例如 `user#30m`
- 检查自定义注解中的 SpEL 是否书写正确：模板字段用 `#{...}`，条件字段用原生 SpEL

### 7.2 数据更新后仍然读到旧值？

- 先确认你使用的是 Spring Cache 路径还是 `RedisUtils` / 自定义注解路径
- 如果更新时直接操作了 Redis，而读取仍走 Spring Cache，则一级本地缓存可能在 TTL 窗口内返回旧值
- 对强一致场景，建议更新后显式驱逐对应 Spring Cache，或统一走 Redis 直连路径

### 7.3 `@CacheEvict(allEntries = true)` 会清到哪里？

- 有 `keyPrefix` 时，会按 `keyPrefix + "*"` 批量删除
- 没有 `keyPrefix` 时，删除范围非常大，需谨慎使用
- 多应用共享 Redis 时，务必配合 `cy.cache.redis.keyPrefix` 做隔离

### 7.4 监听器 / 限流 / 锁为什么异常？

- 检查 Redis 连接与权限是否正常
- 检查 Redis 是否开启了相应事件通知配置（监听器场景）
- 检查是否存在多个应用共用同一批 Key、但未设置 `keyPrefix` 的情况

---

> **作者**：pcy  
> **最后更新**：2026-04-14
