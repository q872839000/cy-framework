# cy-common-ai

统一的 AI 客户端组件，提供对多种大语言模型的访问能力。

## 设计理念

本组件参考 Spring AI 的设计风格，采用简洁的配置结构和清晰的抽象层次：

- **扁平化配置**：避免多层嵌套，配置直观易懂
- **Provider 隔离**：每个 provider 独立配置，互不干扰
- **协议适配**：支持 OpenAI 兼容协议，可接入多种模型厂商
- **统一门面**：通过 `AiFacade` 提供统一的调用接口

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.cy</groupId>
    <artifactId>cy-common-ai</artifactId>
</dependency>
```

### 2. 配置 Provider

在 `application.yml` 或 `bootstrap.yml` 中配置：

```yaml
cy:
  ai:
    # 默认使用的 provider（可选）
    default-provider: zhipu
    
    # Provider 配置
    providers:
      # 智谱 AI
      zhipu:
        api-key: ${ZHIPU_API_KEY}
        base-url: https://open.bigmodel.cn/api/paas
        chat:
          options:
            model: glm-4-air
            max-tokens: 14096
            temperature: 0.7
      
      # 自定义 OpenAI 兼容服务
      custom:
        api-key: ${CUSTOM_API_KEY}
        base-url: https://your-custom-endpoint.com
        chat:
          options:
            model: gpt-4-turbo
            max-tokens: 8192
            temperature: 0.8
```

### 3. 使用 AiFacade

```java
@Autowired
private AiFacade aiFacade;

// 使用默认 provider
public Flux<String> chat(String userMessage) {
    ChatCompletionRequest request = ChatCompletionRequest.builder()
        .addMessage(ChatMessage.user(userMessage))
        .build();
    
    return aiFacade.chatStream(request);
}

// 指定 provider
public Flux<String> chatWithCustomProvider(String userMessage) {
    ChatCompletionRequest request = ChatCompletionRequest.builder()
        .addMessage(ChatMessage.user(userMessage))
        .build();
    
    return aiFacade.chatStream("custom", request);
}

// 简化调用
public String simpleChat(String userMessage) {
    return aiFacade.simpleChat("你是一个助手", userMessage);
}
```

## 配置说明

### Provider 配置项

| 配置项 | 说明 | 必填 | 默认值 |
|--------|------|------|--------|
| `api-key` | API 密钥 | 是 | - |
| `base-url` | API 基础 URL | 是 | - |
| `chat.options.model` | 模型 ID | 是 | - |
| `chat.options.max-tokens` | 最大 token 数 | 否 | 4096 |
| `chat.options.temperature` | 温度参数 | 否 | 0.7 |
| `chat.options.top-p` | Top-P 采样 | 否 | 1.0 |
| `chat.options.frequency-penalty` | 频率惩罚 | 否 | 0.0 |
| `chat.options.presence-penalty` | 存在惩罚 | 否 | 0.0 |

### 支持的模型厂商

所有支持 OpenAI 兼容协议的厂商都可以接入，包括但不限于：

- **OpenAI**：GPT-4, GPT-3.5 等
- **智谱 AI**：GLM-4, GLM-4-Air 等
- **DeepSeek**：DeepSeek-Chat 等
- **Kimi**：Moonshot 系列
- **MiniMax**：通过 ModelVerse 接入
- **自定义服务**：任何 OpenAI 兼容的 API

## API 说明

### AiFacade

统一的 AI 调用门面，提供以下方法：

#### 流式调用

```java
// 使用默认 provider
Flux<String> chatStream(ChatCompletionRequest request)

// 指定 provider
Flux<String> chatStream(String providerName, ChatCompletionRequest request)
```

#### 同步调用

```java
// 使用默认 provider
ChatCompletionResponse chatSync(ChatCompletionRequest request)

// 指定 provider
ChatCompletionResponse chatSync(String providerName, ChatCompletionRequest request)
```

#### 简化调用

```java
// 快速发起对话（使用默认 provider）
String simpleChat(String systemPrompt, String userMessage)
```

### ChatCompletionRequest

对话请求构建器：

```java
ChatCompletionRequest request = ChatCompletionRequest.builder()
    .addMessage(ChatMessage.system("你是一个助手"))
    .addMessage(ChatMessage.user("你好"))
    .temperature(0.8)
    .maxTokens(2000)
    .build();
```

### ChatMessage

消息构建器：

```java
// 系统消息
ChatMessage.system("你是一个助手")

// 用户消息
ChatMessage.user("你好")

// 助手消息
ChatMessage.assistant("你好！有什么可以帮助你的吗？")

// 工具调用消息
ChatMessage.tool("tool-call-id", "result")
```

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                       AiFacade                          │
│  (统一门面，路由到不同 provider)                          │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                    AiProvider 接口                       │
│  (协议抽象层)                                             │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              OpenAiCompatibleProvider                   │
│  (OpenAI 兼容协议实现)                                    │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                   WebClient (HTTP)                      │
│  (底层 HTTP 客户端)                                       │
└─────────────────────────────────────────────────────────┘
```

## 扩展指南

### 添加新的协议适配器

如果需要支持非 OpenAI 兼容的协议（如 Anthropic Claude、Google Gemini），可以实现 `AiProvider` 接口：

```java
@Component
public class CustomProtocolProvider implements AiProvider {
    
    @Override
    public String name() {
        return "custom-protocol";
    }
    
    @Override
    public Flux<String> chatStream(ChatCompletionRequest request) {
        // 实现协议转换和调用逻辑
    }
    
    @Override
    public ChatCompletionResponse chatSync(ChatCompletionRequest request) {
        // 实现同步调用逻辑
    }
}
```

然后在配置中注册：

```yaml
cy:
  ai:
    providers:
      my-custom:
        # 自定义配置项
```

## 最佳实践

1. **使用环境变量管理 API Key**：避免将密钥硬编码在配置文件中
2. **合理设置超时时间**：根据模型响应速度调整 `read-timeout`
3. **流式优先**：对于长文本生成，优先使用 `chatStream` 提供更好的用户体验
4. **错误处理**：捕获并处理 `AiException`，提供友好的错误提示
5. **Token 控制**：根据实际需求设置 `max-tokens`，避免不必要的成本

## 常见问题

### Q: 如何切换不同的模型？

A: 在调用时指定 provider 名称即可：

```java
aiFacade.chatStream("zhipu", request);  // 使用智谱
aiFacade.chatStream("custom", request); // 使用自定义 provider
```

### Q: 如何配置多个相同厂商的模型？

A: 配置多个 provider，使用不同的名称和 model ID：

```yaml
cy:
  ai:
    providers:
      zhipu-fast:
        api-key: ${ZHIPU_API_KEY}
        base-url: https://open.bigmodel.cn/api/paas
        chat:
          options:
            model: glm-4-flash
      
      zhipu-pro:
        api-key: ${ZHIPU_API_KEY}
        base-url: https://open.bigmodel.cn/api/paas
        chat:
          options:
            model: glm-4-pro
```

### Q: 如何处理 API 调用失败？

A: 捕获 `AiException` 并根据错误类型处理：

```java
try {
    String response = aiFacade.simpleChat(systemPrompt, userMessage);
} catch (AiException e) {
    log.error("AI 调用失败: {}", e.getMessage(), e);
    // 处理错误，如返回默认响应或重试
}
```

## 更新日志

### v1.0.0 (2026-04)

- 重构配置结构，对齐 Spring AI 风格
- 简化架构，删除复杂的中间层
- 支持多 provider 配置
- 提供统一的 AiFacade 门面
