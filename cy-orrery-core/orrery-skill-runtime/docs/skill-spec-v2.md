# PromptSkill frontmatter 规范（SKILL.md Spec v2）

本文件是 Orrery 生态提示词技能（PromptSkill）frontmatter 的唯一规范文本；
`PromptSkillLoader` / `ClasspathSkillLoader` 的解析行为是本规范的可执行形态，两者同步演进。

## 1. 目录组织（对齐 Anthropic Agent Skills / OpenClaw / ClawHub）

```
skills/
  deploy-scenario/        ← 技能目录，目录名即技能名（kebab-case）
    SKILL.md              ← 必需：frontmatter + Markdown 正文（L2）
    filling.md            ← 可选：L3 附属资源（收进 PromptSkill.resources）
```

加载来源两种，语义一致：

| 来源 | 加载器 | 典型消费方 |
|------|--------|-----------|
| 文件系统目录 | `PromptSkillLoader.loadFromDirectory` | butler（workspace/installed/bundled 目录） |
| classpath（含 jar 内） | `ClasspathSkillLoader.load` | 随包发布技能的 Agent（如 scenario-agent） |

## 2. frontmatter 字段

### 2.1 核心字段（Anthropic 规范）

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | 是 | 技能名，kebab-case，须与目录名一致；缺省时回退为目录名 |
| `description` | 是 | 技能名片：做什么 + 何时使用（LLM 选用与可观测的依据） |
| `disable-model-invocation` | 否 | true 时不注入 system prompt（默认 false） |
| `allowed-tools` | 否 | 激活后允许使用的工具范围 |
| `model` | 否 | 指定本技能使用的 LLM 模型 |

### 2.2 平台命名空间 `metadata.orrery.*`（兼容 `metadata.openclaw.*`）

平台运行时自身识别的扩展（emoji / requires.bins / install / sideEffect 等），
由 `PromptSkillLoader` 提取进 `PromptSkill.platformMetadata`。**只有平台运行时
自身消费的字段才允许进入此命名空间。**

### 2.3 消费方私有命名空间 `metadata.<consumer>.*`

消费方的私有扩展（激活策略、装配优先级等）必须放在以自己命名的命名空间下，
经 `PromptSkill.frontmatter` 原始 Map 读取，平台运行时不解析、不校验。

例（scenario-agent 的确定性路由策略，`metadata.scenario.*`）：

```yaml
metadata:
  scenario:
    priority: 20                  # 装配顺序与预算竞争（默认 50，值小者优先）
    routing:                      # 确定性路由条件族（缺省整块 = 永不自动激活）
      intents: [NEW_GENERATION]   # 意图维度（OR 语义）
      issue_categories: [logic]   # 活跃问题类别维度
      any_issue: false            # 任意活跃问题即激活
      tool_names: [deploy_equipment]  # 近期工具维度
      keywords: [部署, 编队]       # 用户消息关键词维度
      hitl_resumption: false      # HITL 审批续跑轮激活
```

## 3. L3 附属资源与 include 展开

- 加载器负责把技能目录内除 SKILL.md 外的 `.md` 文件收进 `PromptSkill.resources`
  （文件名 → 内容），两边来源行为一致；
- **占位符（如 `{{include:文件名}}`）如何展开由消费方决定**（确定性内联、按需读取
  或其他策略），本模型不预设展开语义。scenario-agent 采用加载后确定性内联
  （深度限 1 层，未命中占位符原样保留）。

## 4. 内容边界（技能作者纪律）

| 内容 | 住所 |
|------|------|
| 参数语义（取值含义、单位、边界） | Tool 的 inputSchema description（LLM 无条件可见） |
| 使用纪律、工作流、跨参数编排（何时用、怎么组合） | SKILL.md 正文（条件注入，不占常驻 token） |

技能的价值在渐进披露：不相关轮次不占 prompt。若一段内容每个轮次都必须可见，
它不属于技能，应进内核层 prompt 或工具描述。

## 5. 解析容错

单个技能损坏（frontmatter 非法、IO 失败、目录缺 SKILL.md）跳过该技能并记 warn，
不影响其余技能加载；单文件字节上限 256KB、单源技能数上限 200（两来源一致）。
