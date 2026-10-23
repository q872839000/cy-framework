# cy-common-mq

## 1. 组件定位

`cy-common-mq` 是项目内统一的消息队列抽象组件，屏蔽 Local、Redis、Kafka 的底层 API 差异，为业务提供统一的发布、订阅、序列化、链路追踪、消费确认、重试与死信能力。

当前版本采用 **MQ v2 无传输信封架构**：

- **业务 payload**：始终是 broker 中的消息体本身。
- **framework metadata**：通过 broker 可承载的原生附加结构传递。
- **外部系统消息**：不需要符合本组件私有信封协议，可以直接消费原始字符串、JSON、二进制消息。

> 旧版“消息信封”把 `metadata + payload` 一起序列化进 body，外部系统必须理解组件私有格式。当前版本已经移除这种传输信封，改为 payload / metadata 分离。

## 2. 一分钟选型

| 使用模式 | 选择方式 | 是否跨进程 | 是否持久化 | 是否 ACK / Commit | 是否重试 | 是否 DLQ | 是否适合链路追踪 | 典型场景 |
|---|---|---:|---:|---:|---:|---:|---:|---|
| Local 进程内消息 | `driver=LOCAL` | 否 | 否 | 否 | 否 | 否 | 同进程可用 | 单体内异步解耦、本地开发、单元测试 |
| Redis Pub/Sub 广播 | `driver=REDIS`，订阅不写 `groupId` | 是 | 否 | 否 | 否 | 否 | 不适合跨 MQ 传递 | 缓存失效通知、在线广播、低价值实时通知 |
| Redis Stream 可靠消费 | `driver=REDIS`，订阅写 `groupId` | 是 | 是 | 是 | 是，基于 PEL reclaim | 是 | 适合 | 轻量可靠队列、任务消费、要求失败补偿的 Redis 场景 |
| Kafka 消费组 | `driver=KAFKA`，订阅写 `groupId` 或用默认组 | 是 | 是 | 是 | 是，内置重试 + DLQ | 是 | 适合 | 高吞吐事件流、跨服务异步、削峰、可回放消息 |

## 3. 核心概念

### 3.1 Driver

组件内置三个 driver：

| Driver | 底层实现 | 说明 |
|---|---|---|
| `LOCAL` | JVM 内存订阅表 + Spring 线程池 | 不跨进程，不持久化，适合本地异步解耦 |
| `REDIS` | Redis Pub/Sub + Redis Stream | 是否指定 `groupId` 决定消费语义 |
| `KAFKA` | Spring Kafka | Kafka 原生 topic / partition / consumer group 语义 |

### 3.2 Payload 类型

默认 `Fastjson2PayloadCodec` 的规则如下：

| Java 类型 | 发布时如何编码 | 消费时如何解码 | 适合场景 |
|---|---|---|---|
| `String` | UTF-8 直通，不加 JSON 引号 | UTF-8 字符串 | 外部系统发来的纯文本 |
| `byte[]` | 字节直通 | 字节拷贝 | Protobuf、Avro、加密、压缩、二进制 |
| POJO | JSON UTF-8 | JSON 反序列化为目标类型 | 常规业务对象 |
| `Map` / `JSONObject` | JSON UTF-8 | JSON 对象模型 | 结构灵活的 JSON |
| `Object` | JSON UTF-8 | fastjson2 通用 JSON 模型 | 只确定是合法 JSON，但暂不绑定 POJO |

> 如果外部系统发的是普通文本，例如 `hello world`，监听方法请用 `String` 或 `byte[]`。不要用 `Object`，因为 `Object` 会按 JSON 解析。

### 3.3 Metadata

`MqMetadata` 只保存元数据，不包含 payload：

| 字段 | 说明 |
|---|---|
| `messageId` | 组件生成或由 broker 原生 ID 合成 |
| `topic` | 主题 |
| `key` | 业务 key，Kafka 可用于分区路由 |
| `timestamp` | 消息时间 |
| `traceId` / `spanId` / `parentSpanId` | 调用链上下文 |
| `headers` | 业务自定义头，不能使用 `__cy_mq_` 前缀 |
| `source` | 入站来源 driver |
| `nativeMessageId` | broker 原生 ID，例如 Kafka `partition-offset`、Redis Stream entry id |
| `synthetic` | 是否由消费端合成；外部系统消息通常为 `true` |

业务监听方法可以选择只接收 payload，也可以额外接收 metadata：

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event) {
}

@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
    log.info("messageId={}, traceId={}, synthetic={}",
            metadata.getMessageId(), metadata.getTraceId(), metadata.isSynthetic());
}
```

## 4. 消息协议：无传输信封

### 4.1 当前协议

当前版本不再把消息包装成：

```json
{
  "metadata": {},
  "payload": {}
}
```

而是按 broker 能力分开承载：

| Driver | payload 放在哪里 | metadata 放在哪里 |
|---|---|---|
| Kafka | `ProducerRecord.value()` / `ConsumerRecord.value()` | Kafka record headers |
| Redis Stream | entry field：`payload` | entry 其他 fields，框架字段使用 `__cy_mq_*` |
| Redis Pub/Sub | Pub/Sub message body | 协议不支持，消费端合成 |
| Local | 内存记录 body | 内存记录 headers |

### 4.2 框架保留字段

框架保留字段统一使用 `__cy_mq_` 前缀：

| 字段 | 说明 |
|---|---|
| `__cy_mq_id` | messageId |
| `__cy_mq_ts` | timestamp |
| `__cy_mq_key` | key |
| `__cy_mq_trace_id` | traceId |
| `__cy_mq_span_id` | spanId |
| `__cy_mq_parent_span_id` | parentSpanId |
| `__cy_mq_payload_type` | payload 类型提示，业务可选 |

业务自定义 headers 不能使用 `__cy_mq_` 前缀。Redis Stream 中 `payload` 字段也被保留用于保存业务 payload。

## 5. 快速开始

### 5.1 引入依赖

```xml
<dependency>
    <groupId>com.cy.commons</groupId>
    <artifactId>cy-common-mq</artifactId>
</dependency>
```

### 5.2 配置默认 driver

如果注解或模板没有显式指定 driver，则必须配置默认 driver：

```yml
cy:
  mq:
    enabled: true
    driver: LOCAL
```

生产环境建议显式限制启用的 driver：

```yml
cy:
  mq:
    driver: KAFKA
    enabled-drivers:
      - KAFKA
      - REDIS
```

含义：

- 未显式指定 driver 的发布 / 订阅默认走 `KAFKA`。
- 业务仍可以通过 `driver = "REDIS"` 显式使用 Redis。
- 如果代码引用未启用或未注册的 driver，默认启动期 fail-fast。

## 6. 发布消息

### 6.1 使用 `MqTemplate` 发布

#### 使用默认 driver

```java
mqTemplate.publish("order.created", orderEvent);
```

#### 指定 driver

```java
mqTemplate.publish(MqDriverType.KAFKA, "order.created", orderEvent);
mqTemplate.publish(MqDriverType.REDIS, "cache.invalidated", "user:1001");
mqTemplate.publish(MqDriverType.LOCAL, "local.task", task);
```

#### 指定 key

```java
mqTemplate.publish(MqDriverType.KAFKA, "order.created", "order-1001", orderEvent);
```

Kafka 会用 key 参与分区路由；同 key 通常落到同一 partition，从而获得分区内有序。

#### 指定业务 headers

```java
mqTemplate.publish(MqPublishRequest.builder()
        .driver(MqDriverType.KAFKA)
        .topic("order.created")
        .key("order-1001")
        .payload(orderEvent)
        .headers(Map.of(
                "biz-tenant", "acme",
                "biz-region", "cn-east-1"
        ))
        .build());
```

消费端读取：

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
    String tenant = metadata.getHeaders().get("biz-tenant");
}
```

### 6.2 使用 `@MqPublish` 发布

`@MqPublish` 适合在业务方法执行成功后自动发布事件。

#### 发布返回值

```java
@MqPublish(topic = "order.created", driver = "KAFKA", key = "order:#{#result.orderId}")
public OrderCreatedEvent createOrder(CreateOrderCommand command) {
    return orderService.create(command);
}
```

#### 发布方法参数

```java
@MqPublish(topic = "order.created", driver = "KAFKA", payload = "#{#p0}", key = "order:#{#p0.orderId}")
public void publishOrder(OrderCreatedEvent event) {
}
```

#### 动态 topic

```java
@MqPublish(topic = "order.#{#result.eventType}", driver = "KAFKA", payload = "#{#result}")
public OrderEvent process(OrderCommand command) {
    return orderService.process(command);
}
```

#### 发布字符串模板

```java
@MqPublish(topic = "audit.text", driver = "REDIS", payload = "user=#{#p0}, action=#{#p1}")
public void audit(String userId, String action) {
}
```

#### `@MqPublish` 属性

| 属性 | 说明 |
|---|---|
| `topic` | 必填，支持 `#{...}` SpEL 模板 |
| `driver` | 可选，`LOCAL` / `REDIS` / `KAFKA`，为空使用默认 driver |
| `key` | 可选，支持 SpEL 模板，Kafka 分区路由常用 |
| `payload` | 可选，纯 `#{...}` 返回对象；普通字符串模板返回字符串 |
| `publishNull` | payload 为 `null` 时是否发布，默认 `false` |

> `@MqPublish` 不支持单条消息自定义 headers 和 Redis 单条 `deliveryMode`。需要这些能力时使用 `MqTemplate.publish(MqPublishRequest)`。

## 7. 消费消息

### 7.1 使用 `@MqListener`

#### 单条 payload

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event) {
}
```

#### 单条 payload + metadata

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
}
```

#### 批量 payload

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service", batch = true)
public void onOrders(List<OrderCreatedEvent> events) {
}
```

#### 批量 payload + metadata

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service", batch = true)
public void onOrders(List<OrderCreatedEvent> events, List<MqMetadata> metadataList) {
}
```

### 7.2 支持的监听签名

| 模式 | 方法签名 |
|---|---|
| 单条，只关心 payload | `void m(T payload)` |
| 单条，需要 metadata | `void m(T payload, MqMetadata metadata)` |
| 批量，只关心 payload | `void m(List<T> payloads)` |
| 批量，需要 metadata | `void m(List<T> payloads, List<MqMetadata> metadataList)` |

### 7.3 常见 payload 接收方式

#### 接收外部纯文本

```java
@MqListener(topic = "external.text", driver = "KAFKA", groupId = "text-service")
public void onText(String text, MqMetadata metadata) {
}
```

#### 接收外部 JSON 并绑定 POJO

```java
@MqListener(topic = "external.order", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event) {
}
```

#### 接收外部 JSON 通用对象

```java
@MqListener(topic = "external.json", driver = "REDIS", groupId = "json-service")
public void onJson(JSONObject json, MqMetadata metadata) {
}
```

#### 接收二进制

```java
@MqListener(topic = "external.binary", driver = "KAFKA", groupId = "binary-service")
public void onBinary(byte[] body, MqMetadata metadata) {
}
```

### 7.4 使用 `MqTemplate` 编程式订阅

```java
MqSubscription subscription = mqTemplate.subscribe(
        MqDriverType.REDIS,
        "order.created",
        "order-service",
        OrderCreatedEvent.class,
        (payload, metadata) -> {
            log.info("orderId={}, traceId={}", payload.getOrderId(), metadata.getTraceId());
        }
);

subscription.close();
```

批量订阅：

```java
MqSubscription subscription = mqTemplate.subscribeBatch(
        MqDriverType.KAFKA,
        "order.created",
        "order-service",
        OrderCreatedEvent.class,
        (payloads, metadataList) -> {
            log.info("batch size={}", payloads.size());
        }
);
```

## 8. Local 使用模式

### 8.1 适用场景

Local 是 JVM 进程内消息，不依赖外部中间件。

适合：

- 本地开发和单元测试。
- 单体应用内的异步解耦。
- 不要求跨进程、不要求持久化、不要求失败补偿的轻量任务。

不适合：

- 多实例部署。
- 服务间通信。
- 可靠消息。
- 审计、支付、订单等关键事件。

### 8.2 配置

```yml
cy:
  mq:
    driver: LOCAL
    local:
      async: true
```

| 配置 | 说明 |
|---|---|
| `cy.mq.local.async=true` | 发布后由本地线程池异步投递给订阅者 |
| `cy.mq.local.async=false` | 发布线程同步执行订阅者 |

### 8.3 示例

```java
mqTemplate.publish(MqDriverType.LOCAL, "local.audit", auditEvent);

@MqListener(topic = "local.audit", driver = "LOCAL")
public void onAudit(AuditEvent event) {
}
```

Local 没有 ACK / retry / DLQ 语义。消费异常只记录日志和 metrics，不会被 broker 重新投递。

## 9. Redis Pub/Sub 使用模式

### 9.1 什么时候会使用 Pub/Sub

Redis driver 的订阅规则是：

```text
@MqListener(driver = "REDIS", groupId = "")  -> Redis Pub/Sub
@MqListener(driver = "REDIS", groupId = "...") -> Redis Stream
```

也就是说，**Redis 订阅不指定 `groupId` 就是 Pub/Sub 广播模式**。

### 9.2 适用场景

适合：

- 缓存失效通知。
- 配置刷新通知。
- 在线状态广播。
- 低价值、允许丢失的实时事件。

不适合：

- 要求可靠投递的业务消息。
- 要求 ACK / 重试 / DLQ 的消息。
- 要求消费者离线后补消费的消息。
- 要求跨 MQ 边界传递完整 trace metadata 的链路。

### 9.3 配置

```yml
spring:
  data:
    redis:
      host: localhost
      port: 6379

cy:
  mq:
    driver: REDIS
```

### 9.4 发布和订阅

发布：

```java
mqTemplate.publish(MqDriverType.REDIS, "cache.invalidated", "user:1001");
```

订阅，不指定 `groupId`：

```java
@MqListener(topic = "cache.invalidated", driver = "REDIS")
public void onCacheInvalidated(String cacheKey) {
    localCache.evict(cacheKey);
}
```

### 9.5 Pub/Sub 的限制

Redis Pub/Sub 只有 channel 和 message body：

```text
PUBLISH cache.invalidated user:1001
```

因此：

- 消息不持久化。
- 消费者不在线就收不到。
- 没有 ACK。
- 没有 pending。
- 没有 retry。
- 没有 DLQ。
- 不传 framework metadata，消费端 `MqMetadata` 会合成，`synthetic=true`。

## 10. Redis Stream 使用模式

### 10.1 什么时候会使用 Stream

Redis driver 订阅时只要指定 `groupId`，就走 Redis Stream：

```java
@MqListener(topic = "order.created", driver = "REDIS", groupId = "order-service")
public void onOrder(OrderCreatedEvent event) {
}
```

> Redis Stream 必须显式指定 `groupId`。未指定 `groupId` 不会使用 `cy.mq.redis.default-group-id` 自动转为 Stream，而是 Pub/Sub。

### 10.2 适用场景

适合：

- 需要 Redis 内完成轻量可靠队列。
- 消费者可能重启，需要补消费。
- 需要 ACK、失败保留、pending reclaim。
- 需要不可重试失败进入 DLQ。
- 需要 traceId 随消息跨服务传递。

不适合：

- 超高吞吐事件流。
- 需要 Kafka 分区、回放、长期保留、复杂消费者生态的场景。

### 10.3 配置

```yml
spring:
  data:
    redis:
      host: localhost
      port: 6379

cy:
  mq:
    driver: REDIS
    redis:
      consumer-name: auto
      poll-timeout: 2s
      batch-size: 10
      pending-claim-batch-size: 10
      default-delivery-mode: BOTH
    retry:
      pending-timeout: 5m
      pending-scan-interval: 30s
      stream-max-backoff: 60s
      dlq-enabled: true
      dlq-topic-suffix: .DLQ
      commit-on-dlq-failure: false
```

### 10.4 发布模式：`PUBSUB` / `STREAM` / `BOTH`

Redis driver 发布时支持 `MqDeliveryMode`：

| 投递模式 | 行为 | 适用场景 |
|---|---|---|
| `PUBSUB` | 只执行 `PUBLISH topic payload` | 只需要在线广播 |
| `STREAM` | 只执行 `XADD topic * payload ...` | 只需要可靠消费 |
| `BOTH` | 同时写 Pub/Sub 和 Stream | 同一事件既要实时广播，又要可靠消费 |
| `DEFAULT` | 使用 `cy.mq.redis.default-delivery-mode` | 统一默认策略 |

默认 `cy.mq.redis.default-delivery-mode=BOTH`。

使用模板指定单条消息投递模式：

```java
mqTemplate.publish(MqPublishRequest.builder()
        .driver(MqDriverType.REDIS)
        .topic("order.created")
        .payload(orderEvent)
        .deliveryMode(MqDeliveryMode.STREAM)
        .build());
```

### 10.5 Stream 内部消息形态

组件写入 Redis Stream 时使用字段：

```text
XADD order.created *
  payload               '{"orderId":"O1001"}'
  __cy_mq_id            '...'
  __cy_mq_ts            '...'
  __cy_mq_trace_id      '...'
  __cy_mq_span_id       '...'
  biz-tenant            'acme'
```

其中：

- `payload` 是业务消息体原始字节。
- `__cy_mq_*` 是框架 metadata。
- 其他字段是业务 headers。

### 10.6 外部系统写 Redis Stream

外部系统只要按约定写 `payload` 字段即可：

```text
XADD order.created * payload '{"orderId":"O1001","amount":88.6}'
```

组件消费：

```java
@MqListener(topic = "order.created", driver = "REDIS", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
    log.info("external={}, streamId={}", metadata.isSynthetic(), metadata.getNativeMessageId());
}
```

外部系统也可以附加普通字段：

```text
XADD order.created * payload '{"orderId":"O1001"}' source erp tenant acme
```

这些字段会进入 `metadata.getHeaders()`。

### 10.7 Stream 失败处理

| 消费结果 | Redis Stream 行为 |
|---|---|
| 正常返回 / `SUCCESS` | `XACK` |
| 解码失败 / `BAD_MESSAGE` | `XACK` 跳过，不进 DLQ |
| 可重试异常 / `RETRY` | 不 ACK，保留在 PEL，等待 pending reclaim |
| 不可重试异常 / `DROP` | 先写 `<topic><dlqTopicSuffix>`，成功后 ACK |

DLQ 写失败时：

- `commit-on-dlq-failure=false`：默认安全档位，不 ACK，消息留在 PEL 等待 reclaim，避免丢失。
- `commit-on-dlq-failure=true`：强制 ACK，消息会丢失，但不会阻塞通道。

## 11. Kafka 使用模式

### 11.1 适用场景

适合：

- 高吞吐跨服务事件流。
- 多消费者组各自独立消费同一 topic。
- 需要分区内有序。
- 需要失败重试、DLQ、offset 管理。
- 需要消息可回放和较强生态能力。

### 11.2 配置

```yml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      auto-offset-reset: earliest

cy:
  mq:
    driver: KAFKA
    kafka:
      default-group-id: cy-mq
      poll-timeout: 1s
      publish-timeout: 10s
      skip-bad-messages: false
    retry:
      max-retries: 3
      initial-backoff: 1s
      max-backoff: 1m
      backoff-multiplier: 2.0
      dlq-enabled: true
      dlq-topic-suffix: .DLQ
      commit-on-dlq-failure: false
```

> MQ 组件会创建专用 `String` key + `byte[]` value 的 KafkaTemplate 和 ConsumerFactory，并强制关闭 Kafka consumer auto commit，由 driver 根据 `ConsumeDecision` 显式 commit。业务不用把全局 Kafka serializer/deserializer 改成 byte[]。

### 11.3 发布和订阅

发布：

```java
mqTemplate.publish(MqDriverType.KAFKA, "order.created", "order-1001", orderEvent);
```

订阅：

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
}
```

如果 Kafka 监听未写 `groupId`，driver 会使用 `cy.mq.kafka.default-group-id`。

### 11.4 Kafka 内部消息形态

```text
ProducerRecord
  topic   = order.created
  key     = order-1001
  value   = {"orderId":"O1001"}
  headers = __cy_mq_id / __cy_mq_ts / __cy_mq_trace_id / ...
```

业务 payload 始终在 Kafka value 中，不需要框架信封。

### 11.5 外部系统发 Kafka 消息

外部系统可以直接发送：

```text
topic = order.created
key   = order-1001
value = {"orderId":"O1001","amount":88.6}
```

组件消费：

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
    log.info("synthetic={}, nativeId={}", metadata.isSynthetic(), metadata.getNativeMessageId());
}
```

外部消息没有 `__cy_mq_*` headers 时：

- payload 仍正常解码。
- metadata 由 broker 原生信息合成。
- `metadata.isSynthetic()` 为 `true`。
- traceId 会在消费侧新建，不会和外部上游自动串联。

### 11.6 Kafka DLQ

Kafka DLQ 是失败消息专用 topic，默认命名：

```text
原 topic: order.created
DLQ topic: order.created.DLQ
```

进入 DLQ 的典型情况：

- 业务抛出不可重试异常，映射为 `DROP`。
- 业务抛出可重试异常，Kafka driver 重试耗尽后进入 DLQ。

Kafka DLQ 中保存：

- 原始 payload。
- 原始 headers。
- 原 topic、partition、offset。
- 错误类名。
- DLQ 写入时间。

关键可靠性规则：

```text
先写 DLQ 成功，再 commit 原 topic offset。
```

如果 DLQ 写失败：

- `commit-on-dlq-failure=false`：默认安全档位，不 commit，Kafka 后续会重新投递，且同 partition 后续 offset 不会越过这条失败消息。
- `commit-on-dlq-failure=true`：强制 commit，消息会丢失，但通道不阻塞。

## 12. 外部系统消息消费指南

### 12.1 外部系统发送字符串

Kafka：

```text
value = hello world
```

Redis Pub/Sub：

```text
PUBLISH external.text "hello world"
```

Redis Stream：

```text
XADD external.text * payload "hello world"
```

消费：

```java
@MqListener(topic = "external.text", driver = "KAFKA", groupId = "text-service")
public void onText(String text) {
}
```

### 12.2 外部系统发送 JSON 对象

消息体：

```json
{"orderId":"O1001","amount":88.6}
```

消费为 POJO：

```java
@MqListener(topic = "external.order", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event) {
}
```

消费为通用 JSON：

```java
@MqListener(topic = "external.order", driver = "KAFKA", groupId = "order-service")
public void onOrder(JSONObject json) {
}
```

### 12.3 外部系统发送二进制

消费：

```java
@MqListener(topic = "external.bin", driver = "KAFKA", groupId = "bin-service")
public void onBinary(byte[] body) {
}
```

如果是 Redis Stream，外部系统必须把二进制放在 `payload` 字段。

## 13. 分布式追踪

### 13.1 Kafka 和 Redis Stream

Kafka 与 Redis Stream 能随消息传递 trace metadata：

- 发布端从 `InvocationContext` 或 MDC 捕获 `traceId` / `spanId` / `parentSpanId`。
- 写入 Kafka headers 或 Redis Stream fields。
- 消费端恢复 traceId，生成新的 spanId，把发布端 spanId 作为 parentSpanId。
- 业务日志可以按同一个 traceId 串联。

### 13.2 Redis Pub/Sub

Redis Pub/Sub 不适合跨 MQ 边界传递完整调用链，因为协议没有 metadata 通道。

Pub/Sub 消费端会：

- 合成 `MqMetadata`。
- 生成新的 traceId。
- 记录本地消费日志。

如果必须在 Pub/Sub 里串联上游 trace，只能由业务自己把 trace 信息放进 payload，但这会变成业务自定义信封，不作为组件默认方案。

## 14. 消费异常、重试和 DLQ

### 14.1 业务异常映射

| 业务行为 | Runtime 映射 | 说明 |
|---|---|---|
| 正常返回 | `SUCCESS` | 消费成功 |
| payload 解码失败 | `BAD_MESSAGE` | 坏消息 |
| 抛 `MqConsumeException(..., retryable=false)` | `DROP` | 不可重试 |
| 抛普通异常或 `retryable=true` | `RETRY` | 可重试 |

### 14.2 示例

```java
@MqListener(topic = "order.created", driver = "KAFKA", groupId = "order-service")
public void onOrder(OrderCreatedEvent event, MqMetadata metadata) {
    try {
        orderService.process(event);
    } catch (ExternalServiceUnavailableException e) {
        throw new MqConsumeException(metadata.getTopic(), metadata.getMessageId(), "外部服务暂不可用", e, true);
    } catch (InvalidOrderException e) {
        throw new MqConsumeException(metadata.getTopic(), metadata.getMessageId(), "订单数据非法", e, false);
    }
}
```

### 14.3 各 driver 行为

| Decision | Local | Redis Pub/Sub | Redis Stream | Kafka |
|---|---|---|---|---|
| `SUCCESS` | 记录成功 | 记录成功 | ACK | commit offset |
| `BAD_MESSAGE` | 记录失败 | 记录失败 | ACK 跳过 | commit 跳过 |
| `RETRY` | 记录失败 | 记录失败，不重投 | 不 ACK，PEL reclaim | 内部重试，耗尽后 DLQ |
| `DROP` | 记录失败 | 记录失败 | DLQ 成功后 ACK | DLQ 成功后 commit |

## 15. 配置清单

### 15.1 基础配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `cy.mq.enabled` | `true` | 是否启用 MQ 组件 |
| `cy.mq.driver` | 未配置 | 默认 driver；未配置时所有使用默认 driver 的调用会 fail-fast |
| `cy.mq.enabled-drivers` | 空 | 启用所有已发现 driver；非空时只启用指定集合 |
| `cy.mq.spel-fail-fast` | `true` | `@MqPublish` SpEL 解析失败是否抛异常 |
| `cy.mq.validation.fail-fast-on-missing-driver` | `true` | 引用缺失 driver 时是否启动失败 |

### 15.2 Redis 配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `cy.mq.redis.consumer-name` | 主机名 + UUID 后缀 | Redis Stream consumer 名 |
| `cy.mq.redis.poll-timeout` | `2s` | Stream `XREADGROUP` 阻塞时间 |
| `cy.mq.redis.batch-size` | `10` | Stream 单次读取数量 |
| `cy.mq.redis.pending-claim-batch-size` | `10` | 每次 pending reclaim 数量 |
| `cy.mq.redis.default-delivery-mode` | `BOTH` | Redis 发布默认投递模式 |

### 15.3 Kafka 配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `cy.mq.kafka.default-group-id` | `cy-mq` | Kafka 监听未指定 groupId 时使用 |
| `cy.mq.kafka.poll-timeout` | `1s` | Kafka poll 超时 |
| `cy.mq.kafka.publish-timeout` | `10s` | Kafka publish 等待 broker ACK 的超时 |
| `cy.mq.kafka.skip-bad-messages` | `false` | 批量消费遇到坏消息时是否跳过 |

### 15.4 Retry / DLQ 配置

| 配置 | 默认值 | 说明 |
|---|---|---|
| `cy.mq.retry.max-retries` | `3` | Kafka 可重试异常最大重试次数，`-1` 表示无限重试 |
| `cy.mq.retry.initial-backoff` | `1s` | 初始退避 |
| `cy.mq.retry.max-backoff` | `1m` | 最大退避 |
| `cy.mq.retry.stream-max-backoff` | `60s` | Redis Stream 消费循环异常最大退避 |
| `cy.mq.retry.backoff-multiplier` | `2.0` | 指数退避倍数 |
| `cy.mq.retry.dlq-enabled` | `true` | 是否启用 DLQ |
| `cy.mq.retry.dlq-topic-suffix` | `.DLQ` | DLQ topic / stream 后缀 |
| `cy.mq.retry.commit-on-dlq-failure` | `false` | DLQ 写失败时是否仍 commit / ACK |
| `cy.mq.retry.pending-timeout` | `5m` | Redis Stream pending 超过该时间可被 reclaim |
| `cy.mq.retry.pending-scan-interval` | `30s` | Redis Stream pending 扫描间隔 |

### 15.5 线程池配置

```yml
cy:
  mq:
    executors:
      local:
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 1000
        rejection-policy: CALLER_RUNS
      redis:
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 0
        rejection-policy: ABORT
      kafka:
        core-pool-size: 2
        max-pool-size: 8
        queue-capacity: 0
        rejection-policy: ABORT
```

Redis / Kafka 消费线程池建议 `queue-capacity=0`，让线程池优先扩容而不是长期排队。

## 16. 自动装配

组件通过 Spring Boot 3 auto-configuration 自动注册：

| Bean | 条件 |
|---|---|
| Local driver | 总是注册 |
| Redis driver | 存在 `RedisConnectionFactory` / `StringRedisTemplate` |
| Kafka driver | classpath 有 Kafka 且配置 `spring.kafka.bootstrap-servers` |
| `MqTemplate` | 注册 driver registry 后自动注册 |
| `@MqPublish` AOP | 开启 MQ 组件后自动注册 |
| `@MqListener` registrar | 开启 MQ 组件后自动扫描 |

所有 driver 默认复用 Spring Boot 标准连接配置：

- Redis：`spring.data.redis.*`
- Kafka：`spring.kafka.*`

## 17. 常见问题

### 17.1 发了消息但没收到

优先检查：

- 发布和订阅 driver 是否一致。
- topic 是否完全一致。
- Redis 是否误用了 Pub/Sub：订阅没有 `groupId` 就是 Pub/Sub。
- Kafka groupId 是否符合预期。
- `cy.mq.enabled-drivers` 是否排除了目标 driver。

### 17.2 Redis 为什么一个消息多个实例都收到了

因为没有指定 `groupId`，走的是 Redis Pub/Sub 广播。

如果希望同一组内只有一个实例处理，必须指定 `groupId`：

```java
@MqListener(topic = "task.created", driver = "REDIS", groupId = "task-workers")
public void onTask(TaskCreatedEvent event) {
}
```

### 17.3 Redis Stream 外部消息为什么 payload 为空

Redis Stream 外部消息必须把业务体放到 `payload` 字段：

```text
XADD topic * payload '{"id":1}'
```

如果写成：

```text
XADD topic * body '{"id":1}'
```

组件会把 `body` 当成普通 header，真正的 payload 为空。

### 17.4 Kafka 是否需要配置 `enable-auto-commit=true`

不需要。MQ 组件会为自身 Kafka consumer 强制设置：

```text
enable.auto.commit=false
```

offset 由 driver 根据 `ConsumeDecision` 显式 commit。

### 17.5 Redis Pub/Sub 能不能保证 traceId 传递

不能保证。Redis Pub/Sub 没有 metadata 通道。需要完整跨服务 trace 的场景请使用 Kafka 或 Redis Stream。

### 17.6 什么时候开启 `commit-on-dlq-failure=true`

只有在可以接受消息丢失、且更关注主通道不阻塞时才开启。

默认应保持：

```yml
cy:
  mq:
    retry:
      commit-on-dlq-failure: false
```

## 18. 最佳实践

- **可靠业务消息优先 Kafka 或 Redis Stream**：不要用 Redis Pub/Sub 承载订单、支付、审计等关键消息。
- **Redis 可靠消费必须指定 `groupId`**：否则就是 Pub/Sub 广播。
- **外部系统接入时先确认 payload 格式**：文本用 `String`，JSON 用 POJO / `JSONObject`，二进制用 `byte[]`。
- **业务 headers 不要使用 `__cy_mq_` 前缀**：这是框架保留命名空间。
- **Kafka 用 key 保证分区内顺序**：同一业务实体建议使用稳定 key。
- **DLQ 写失败默认不要 commit / ACK**：用阻塞换不丢消息。
- **消费端做好幂等**：任何 MQ 都可能因为重试、重启、reclaim 等产生重复投递。
- **生产环境显式配置 `enabled-drivers`**：避免环境依赖变化导致意外注册 driver。

## 19. 架构流转

### 19.1 发布链路

```text
业务代码 / @MqPublish
  -> MqTemplate
  -> MqProducerRuntime
     1. 选择 driver
     2. 构造 MqMetadata
     3. 捕获 trace
     4. MqMessageCodec.encode(metadata, payload)
  -> MqOutboundRecord(body, headers)
  -> Driver 写 broker
```

### 19.2 消费链路

```text
Driver 拉取 broker 原始消息
  -> MqInboundRecord(body, headers, nativeMessageId)
  -> MqConsumerRuntime
     1. MqMessageCodec.decode(record, payloadType)
     2. 还原或合成 MqMetadata
     3. 建立 trace scope
     4. 调用业务 listener
     5. 异常映射为 ConsumeDecision
  -> Driver 根据 decision 执行 ACK / commit / retry / DLQ
```

## 20. 版本历史

| 版本 | 日期 | 变更内容 |
|---|---|---|
| 2.0.0 | 2026-05 | MQ v2 重构：移除传输层 `MqEnvelope`，改为 payload / metadata 分离；支持外部系统原生消息；Kafka / Redis Stream 支持可靠 DLQ；Redis Stream 字节安全与 PEL reclaim；Kafka offset 安全修复 |
| 1.2.0 | 2026-01 | 合并 Redis Pub/Sub 与 Redis Stream 到 `REDIS` driver，通过 `groupId` 区分消费模式 |
| 1.1.0 | 2026-01 | 新增 Redis Stream driver |
| 1.0.0 | 2026-01 | 初始版本，支持 Local / Redis / Kafka |

---

> **作者**：pcy  
> **最后更新**：2026-05
