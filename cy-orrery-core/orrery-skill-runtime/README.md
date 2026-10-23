# orrery-skill-runtime

技能执行引擎，为 Orrery 平台提供内置工具沙箱、安全策略、工具注册与执行能力。

---

## 1. 组件能力总览

| 能力 | 说明 |
|------|------|
| **技能执行引擎** | 统一的技能执行入口，支持技能注册、查询、执行和结果返回 |
| **内置工具集** | 9 个开箱即用的工具：文件读写、命令执行、网页抓取、进程管理、系统信息、记忆检索等 |
| **沙箱隔离** | 强制 FILESYSTEM / EXECUTION 类工具在沙箱内执行，防止路径越界和符号链接逃逸 |
| **命令过滤** | 三层防御：危险关键词 + 正则黑名单 + 正则白名单 |
| **超时控制** | 全局超时上限和默认超时，防止单个工具阻塞编排循环 |
| **工具分类** | 6 大分类（文件系统、命令执行、记忆、网络、会话、通用），支持按分类过滤 |
| **副作用级别** | 对齐 MCP 协议，标记工具的副作用等级（NONE / MODERATE / HIGH），用于权限控制 |
| **参数 Schema** | 每个工具提供 JSON Schema 描述参数，便于 LLM function-calling 和前端表单生成 |

---

## 2. 环境要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| **Java** | 21+ | 使用 record、pattern matching 等语法特性 |
| **Spring Boot** | 3.x | 自动配置支持 |
| **cy-orrery-protocol** | 同版本 | 提供 SideEffectLevel、SkillDescriptor 等协议模型 |
| **cy-common-core** | 同版本 | 提供异常处理、日志等基础设施 |

---

## 3. 引入依赖

```xml
<dependency>
    <groupId>com.cy.orrery</groupId>
    <artifactId>orrery-skill-runtime</artifactId>
</dependency>
```

组件通过 Spring Boot `AutoConfiguration.imports` 自动注册，无需手动 `@Import`。

---

## 4. 安全机制详解

### 4.1 纵深防御架构

orrery-skill-runtime 采用 **Defense in Depth** 设计，在多个层面防止安全风险：

```
┌─────────────────────────────────────────────────────┐
│ 1. ownerOnly 访问控制                               │
│    只有 Agent owner 可调用高危工具                  │
├─────────────────────────────────────────────────────┤
│ 2. 沙箱强制约束                                     │
│    FILESYSTEM / EXECUTION 类工具必须在沙箱内执行   │
├─────────────────────────────────────────────────────┤
│ 3. 命令三层过滤                                     │
│    危险关键词 → 正则黑名单 → 正则白名单            │
├─────────────────────────────────────────────────────┤
│ 4. 路径安全校验                                     │
│    符号链接逃逸检测 + 沙箱边界检查                  │
├─────────────────────────────────────────────────────┤
│ 5. 超时控制                                         │
│    全局超时上限 + 默认超时，防止资源耗尽           │
└─────────────────────────────────────────────────────┘
```

### 4.2 ToolSecurityPolicy（安全策略）

`ToolSecurityPolicy` 是平台级安全策略组件，统一管控所有内置工具的执行边界。

#### 沙箱策略

```java
// 强制要求 FILESYSTEM / EXECUTION 类工具在沙箱内执行
Set<ToolCategory> sandboxRequiredCategories = EnumSet.of(
    ToolCategory.FILESYSTEM, ToolCategory.EXECUTION
);

// 默认沙箱根路径（生产环境应配置为隔离的临时目录）
Path defaultSandboxRoot = Path.of("/tmp/orrery-sandbox");

// 默认工作目录
Path defaultWorkingDirectory = Path.of("/tmp/orrery-sandbox");
```

#### 命令过滤（三层防御）

**第一层：危险关键词检查**

```java
List<String> blockedCommandKeywords = List.of(
    "rm -rf /", "rm -rf /*",
    "mkfs", "dd if=",
    ":(){ :|:& };:",  // Fork bomb
    "chmod -R 777 /",
    "shutdown", "reboot", "halt", "poweroff",
    "init 0", "init 6",
    "> /dev/sda", "format c:"
);
```

**第二层：正则黑名单**

```java
List<Pattern> deniedCommandPatterns = new ArrayList<>();
// 示例：拦截所有包含 "sudo" 的命令
deniedCommandPatterns.add(Pattern.compile(".*\\bsudo\\b.*"));
```

**第三层：正则白名单**

```java
List<Pattern> allowedCommandPatterns = new ArrayList<>();
// 示例：仅允许 ls、cat、grep 等安全命令
allowedCommandPatterns.add(Pattern.compile("^(ls|cat|grep|find)\\s.*"));
```

执行顺序：黑名单优先 → 白名单为空时默认放行 → 白名单非空时仅放行匹配的命令

#### 路径安全校验

```java
// 沙箱模式下的路径校验流程
protected Path resolveAndAssertPath(String filePath, ToolExecutionContext context) {
    // 1. 相对路径解析为绝对路径
    Path resolved = base.resolve(filePath).normalize();
    
    // 2. 沙箱边界检查
    if (!resolved.startsWith(sandboxRoot)) {
        throw new SecurityException("路径越界");
    }
    
    // 3. 符号链接逃逸检测
    Path realPath = resolved.toRealPath();
    if (!realPath.startsWith(sandboxRoot)) {
        throw new SecurityException("符号链接逃逸");
    }
    
    return resolved;
}
```

#### 超时限制

```java
// 全局工具执行超时上限（毫秒）
long maxTimeoutMs = 120_000L;  // 120 秒

// 默认执行超时（毫秒）
long defaultTimeoutMs = 30_000L;  // 30 秒

// 实际超时 = min(context.timeoutMs, maxTimeoutMs)
```

### 4.3 关于 ShellExecTool 的命令注入风险说明

`ShellExecTool` 需要支持复杂的 shell 特性（管道、重定向、变量替换等），因此必须通过 shell 解释器执行命令。虽然使用了 ProcessBuilder 的数组参数模式，但在 Windows 平台上为了解决 UTF-8 编码问题，仍需要字符串拼接：

```java
// Windows 平台
pb = new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul && " + command);

// Unix/Linux 平台
pb = new ProcessBuilder("/bin/sh", "-c", command);
```

这种设计在理论上存在命令注入风险，但通过以下机制将风险降至最低：

1. **命令过滤**：命令在执行前必须通过 `ToolSecurityPolicy` 的三层过滤
2. **访问控制**：只有 Agent owner 可以调用此工具（`ownerOnly=true`）
3. **沙箱隔离**：工作目录被强制限制在沙箱根路径内
4. **超时保护**：超时机制防止恶意命令长时间占用资源

**生产环境建议**：配置严格的命令白名单，仅允许必要的命令执行。

---

## 5. 内置工具列表

| 工具 ID | 名称 | 分类 | 副作用 | 说明 |
|---------|------|------|--------|------|
| `file_read` | 文件读取 | FILESYSTEM | NONE | 读取文本文件内容，支持按行号范围分页读取 |
| `file_write` | 文件写入 | FILESYSTEM | HIGH | 向文件写入内容（覆盖或追加），支持创建新文件 |
| `file_edit` | 文件编辑 | FILESYSTEM | HIGH | 编辑文件内容，支持精确的行号替换 |
| `shell_exec` | 命令执行 | EXECUTION | HIGH | 在指定工作目录下执行 Shell 命令，支持超时控制 |
| `process_manage` | 进程管理 | EXECUTION | HIGH | 查询、终止进程，支持按 PID 或进程名过滤 |
| `web_fetch` | 网页抓取 | NETWORK | NONE | 从指定 URL 获取网页内容，支持超时和内容截断 |
| `memory_search` | 记忆检索 | MEMORY | NONE | 从 Agent 记忆库检索相关信息 |
| `system_info` | 系统信息 | UTILITY | NONE | 获取系统信息（CPU、内存、磁盘等） |

### 5.1 工具分类说明

- **FILESYSTEM**：文件系统操作，必须在沙箱内执行
- **EXECUTION**：命令执行与进程管理，必须在沙箱内执行
- **MEMORY**：记忆检索与存储，无沙箱要求
- **NETWORK**：网络请求与数据抓取，无沙箱要求
- **SESSION**：会话与子 Agent 编排，无沙箱要求
- **UTILITY**：通用辅助工具，无沙箱要求

### 5.2 副作用级别说明

- **NONE**：只读操作，无副作用
- **MODERATE**：可能修改系统状态，但影响范围有限
- **HIGH**：可能对系统造成重大影响，需要谨慎使用

---

## 6. 工具开发指南

### 6.1 继承 AbstractBuiltinTool

所有内置工具都继承 `AbstractBuiltinTool`，该基类提供：

- 工具元数据管理（ID、名称、分类、副作用级别）
- 安全前置校验（沙箱约束、ownerOnly 检查）
- 通用参数读取方法（`readStringParam`、`readIntParam` 等）
- 路径安全校验（`resolveAndAssertPath`）

### 6.2 实现 doExecute 方法

```java
public class MyCustomTool extends AbstractBuiltinTool {

    private static final String TOOL_ID = "my_custom_tool";
    private static final String TOOL_NAME = "我的自定义工具";
    private static final String TOOL_SUMMARY = "工具功能描述";

    private static final String PARAMETER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "param1": {
                  "type": "string",
                  "description": "参数1说明"
                },
                "param2": {
                  "type": "integer",
                  "description": "参数2说明"
                }
              },
              "required": ["param1"]
            }
            """;

    public MyCustomTool() {
        // 副作用级别默认为 NONE
        super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.UTILITY);
        
        // 或显式指定副作用级别
        // super(TOOL_ID, TOOL_NAME, TOOL_SUMMARY, ToolCategory.UTILITY, SideEffectLevel.MODERATE);
    }

    @Override
    public String getParameterSchema() {
        return PARAMETER_SCHEMA;
    }

    @Override
    public boolean isOwnerOnly() {
        // 返回 true 表示仅 owner 可调用
        return false;
    }

    @Override
    protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
        // 1. 读取参数
        String param1 = readStringParam(input, "param1", true);
        int param2 = readIntParam(input, "param2", 0);

        try {
            // 2. 业务逻辑
            String result = doSomething(param1, param2);

            // 3. 返回成功结果
            return successOutput("result", result);
        } catch (Exception e) {
            log.error("执行失败", e);
            return failureOutput("执行失败: " + e.getMessage());
        }
    }

    private String doSomething(String param1, int param2) {
        // 实现具体逻辑
        return "result";
    }
}
```

### 6.3 参数 Schema 定义

参数 Schema 使用 JSON Schema 格式，用于：

- LLM function-calling 时的参数描述
- 前端表单自动生成
- 参数验证

```json
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "文件路径"
    },
    "offset": {
      "type": "integer",
      "description": "起始行号（1-indexed）"
    },
    "limit": {
      "type": "integer",
      "description": "读取行数上限"
    }
  },
  "required": ["path"]
}
```

### 6.4 工具注册

自定义工具需要注册到 `BuiltinToolRegistry`：

```java
@Configuration
public class CustomToolConfiguration {

    @Bean
    public MyCustomTool myCustomTool() {
        return new MyCustomTool();
    }

    @Bean
    public BuiltinToolRegistry builtinToolRegistry(List<BuiltinTool> tools) {
        BuiltinToolRegistry registry = new BuiltinToolRegistry();
        tools.forEach(registry::register);
        return registry;
    }
}
```

---

## 7. 安全配置示例

### 7.1 生产环境（严格模式）

```java
@Configuration
public class SkillRuntimeSecurityConfig {

    @Bean
    public ToolSecurityPolicy toolSecurityPolicy() {
        return ToolSecurityPolicy.strict(
            Path.of("/tmp/orrery-sandbox")
        );
    }
}
```

配置说明：

- 强制沙箱：FILESYSTEM / EXECUTION 类工具必须在沙箱内
- 沙箱根路径：`/tmp/orrery-sandbox`
- 全局超时：120 秒
- 默认超时：30 秒
- 危险命令关键词：默认列表

### 7.2 开发/测试环境（宽松模式）

```java
@Configuration
public class SkillRuntimeSecurityConfig {

    @Bean
    public ToolSecurityPolicy toolSecurityPolicy() {
        return ToolSecurityPolicy.permissive();
    }
}
```

配置说明：

- 不强制沙箱
- 全局超时：300 秒
- 默认超时：60 秒
- 保留危险命令关键词拦截

### 7.3 自定义安全策略

```java
@Configuration
public class SkillRuntimeSecurityConfig {

    @Bean
    public ToolSecurityPolicy toolSecurityPolicy() {
        return ToolSecurityPolicy.builder()
                .defaultSandboxRoot(Path.of("/data/sandbox"))
                .defaultWorkingDirectory(Path.of("/data/sandbox"))
                .maxTimeoutMs(60_000L)  // 60 秒
                .defaultTimeoutMs(20_000L)  // 20 秒
                .deniedCommandPatterns(List.of(
                    Pattern.compile(".*\\bsudo\\b.*"),
                    Pattern.compile(".*\\bsu\\b.*")
                ))
                .allowedCommandPatterns(List.of(
                    Pattern.compile("^(ls|cat|grep|find)\\s.*"),
                    Pattern.compile("^python\\s.*\\.py$")
                ))
                .build();
    }
}
```

---

## 8. 使用示例

### 8.1 文件读取

```java
@Service
@RequiredArgsConstructor
public class FileService {

    private final SkillExecutor skillExecutor;

    public void readFile() {
        Map<String, Object> input = Map.of(
            "path", "src/main/java/Example.java",
            "offset", 1,
            "limit", 50
        );

        SkillExecutionResult result = skillExecutor.execute("file_read", input);
        if (result.isSuccess()) {
            System.out.println(result.getOutput().get("content"));
        }
    }
}
```

### 8.2 命令执行

```java
public void executeCommand() {
    Map<String, Object> input = Map.of(
        "command", "ls -la /tmp",
        "timeoutSec", 30
    );

    SkillExecutionResult result = skillExecutor.execute("shell_exec", input);
    if (result.isSuccess()) {
        Map<String, Object> output = result.getOutput();
        System.out.println("Exit Code: " + output.get("exitCode"));
        System.out.println("Output: " + output.get("output"));
    }
}
```

### 8.3 网页抓取

```java
public void fetchWebPage() {
    Map<String, Object> input = Map.of(
        "url", "https://example.com",
        "maxChars", 50000,
        "timeoutSec", 30
    );

    SkillExecutionResult result = skillExecutor.execute("web_fetch", input);
    if (result.isSuccess()) {
        System.out.println(result.getOutput().get("content"));
    }
}
```

### 8.4 执行上下文配置

```java
public void executeWithContext() {
    ToolExecutionContext context = ToolExecutionContext.builder()
        .sandboxed(true)
        .sandboxRoot(Path.of("/tmp/orrery-sandbox"))
        .workingDirectory(Path.of("/tmp/orrery-sandbox"))
        .timeoutMs(60_000L)
        .ownerAccess(true)
        .envOverrides(Map.of(
            "JAVA_HOME", "/usr/lib/jvm/java-21",
            "PATH", "/usr/local/bin:/usr/bin"
        ))
        .build();

    Map<String, Object> input = Map.of(
        "command", "java -version"
    );

    // 通过 BuiltinToolExecutor 执行
    BuiltinToolExecutor executor = new BuiltinToolExecutor(toolRegistry, securityPolicy);
    Map<String, Object> result = executor.execute("shell_exec", input, context);
}
```

---

## 9. 架构

```
┌─────────────────────────────────────────────────────────────┐
│ SkillExecutor                                               │
│ 技能执行入口，从 SkillRegistry 查找并执行 Skill            │
└────────────────┬────────────────────────────────────────────┘
                 │
        ┌────────▼────────┐
        │ SkillRegistry   │
        │ 技能注册表      │
        └────────┬────────┘
                 │
    ┌────────────┴────────────┐
    │                         │
┌───▼──────────────┐  ┌──────▼──────────────┐
│ BuiltinToolRegistry  │  │ PromptSkillRegistry │
│ 内置工具注册表   │  │ 提示词技能注册表   │
└───┬──────────────┘  └────────────────────┘
    │
    ├─ FileReadTool
    ├─ FileWriteTool
    ├─ FileEditTool
    ├─ ShellExecTool
    ├─ ProcessManageTool
    ├─ WebFetchTool
    ├─ MemorySearchTool
    └─ SystemInfoTool
         │
         ▼
┌─────────────────────────────────────────┐
│ AbstractBuiltinTool                     │
│ ├─ 安全前置校验                         │
│ │  ├─ 沙箱约束检查                      │
│ │  ├─ ownerOnly 访问控制                │
│ │  └─ 上下文补全                        │
│ └─ doExecute(业务逻辑)                  │
└────────────┬────────────────────────────┘
             │
             ▼
┌─────────────────────────────────────────┐
│ ToolSecurityPolicy                      │
│ ├─ 沙箱策略                             │
│ ├─ 命令过滤（三层防御）                 │
│ ├─ 超时限制                             │
│ └─ 路径安全校验                         │
└─────────────────────────────────────────┘
```

---

## 10. 常见问题

### Q1: 如何限制工具只能在沙箱内执行？

A: 在 `ToolSecurityPolicy` 中配置 `sandboxRequiredCategories`：

```java
ToolSecurityPolicy policy = ToolSecurityPolicy.builder()
    .sandboxRequiredCategories(EnumSet.of(
        ToolCategory.FILESYSTEM,
        ToolCategory.EXECUTION
    ))
    .defaultSandboxRoot(Path.of("/tmp/sandbox"))
    .build();
```

### Q2: 如何自定义命令白名单？

A: 在 `ToolSecurityPolicy` 中配置 `allowedCommandPatterns`：

```java
ToolSecurityPolicy policy = ToolSecurityPolicy.builder()
    .allowedCommandPatterns(List.of(
        Pattern.compile("^(ls|cat|grep)\\s.*"),
        Pattern.compile("^python\\s.*\\.py$")
    ))
    .build();
```

### Q3: ShellExecTool 是否安全？

A: ShellExecTool 通过多层防御机制降低风险：

1. 命令必须通过三层过滤（危险关键词 + 黑名单 + 白名单）
2. 仅 owner 可调用（`ownerOnly=true`）
3. 工作目录被限制在沙箱内
4. 超时机制防止资源耗尽

生产环境应配置严格的命令白名单。

### Q4: 如何为自定义工具添加安全策略？

A: 在 `doExecute` 方法中使用 `getSecurityPolicy()` 获取策略实例：

```java
@Override
protected Map<String, Object> doExecute(Map<String, Object> input, ToolExecutionContext context) {
    ToolSecurityPolicy policy = getSecurityPolicy();
    if (policy != null) {
        // 执行安全检查
        ToolSecurityPolicy.CommandCheckResult result = policy.checkCommand(command);
        if (!result.allowed()) {
            return failureOutput("命令被拒绝: " + result.reason());
        }
    }
    // 业务逻辑
}
```

### Q5: 如何处理符号链接逃逸？

A: `resolveAndAssertPath` 方法自动检测符号链接逃逸：

```java
Path resolved = resolveAndAssertPath(filePath, context);
// 如果真实路径不在沙箱内，会抛出 SecurityException
```

---

## 11. 最佳实践

1. **生产环境必须启用沙箱**：配置 `ToolSecurityPolicy.strict()` 或自定义严格策略
2. **配置命令白名单**：不要依赖黑名单，应该显式配置允许的命令
3. **设置合理的超时**：避免工具执行阻塞编排循环
4. **监控工具执行**：记录所有工具执行日志，便于审计和问题排查
5. **定期审查安全策略**：根据实际使用情况调整黑名单和白名单
6. **限制 ownerOnly 工具的调用**：仅在必要时授予 owner 权限
7. **隔离沙箱目录**：使用独立的临时目录作为沙箱根路径，避免与系统目录混淆

---

## 12. 扩展点

| 扩展方向 | 当前实现 | 扩展方式 |
|---------|---------|---------|
| **自定义工具** | 9 个内置工具 | 继承 `AbstractBuiltinTool`，实现 `doExecute` 方法 |
| **安全策略** | `ToolSecurityPolicy` | 自定义 `ToolSecurityPolicy` Bean，配置黑名单/白名单 |
| **工具执行** | `BuiltinToolExecutor` | 实现 `ToolExecutor` 接口，自定义执行逻辑 |
| **工具注册** | `BuiltinToolRegistry` | 实现 `ToolRegistry` 接口，自定义注册机制 |

---

## 13. 相关文档

- [orrery-protocol](../orrery-protocol/README.md) — 协议模型定义
- [orrery-sdk](../orrery-sdk/README.md) — 服务发现 SDK
- [orrery-memory](../orrery-memory/README.md) — 记忆子系统
