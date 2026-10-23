# orrery-agent-runtime

A domain-agnostic general Agent runtime framework: ReAct loop engine + stage-chain
workflow engine + production-grade guardrails (fuses / timeouts / context guard) +
HITL human-in-the-loop + checkpoint persistence. Shared by every agent in the
Orrery ecosystem.

> Chinese documentation: [README.md](README.md)

## Capability Overview

| Capability | Component | Description |
|---|---|---|
| ReAct execution kernel | `loop/ReactLoopEngine` | think→tool→observe loop; convergence & degeneration dual fuses, micro-step detection, round-exhaustion terminus |
| Stage-chain workflow | `pipeline/PipelineEngine` | sealed eight-state `StageOutcome` transitions, rollback budget, transition fuse, hot engine switch |
| Tool-round orchestration | `loop/LoopToolRoundEngine` | approval barrier, deterministic replay, partial-failure semantics, read-only parallel execution |
| Tool guardrails | `tool/GuardedToolInvoker` | hard timeout cancellation, argument-fingerprint fuse, failure-streak escalation |
| Context engineering | `harness/DefaultContextGuard` | token estimation, folding compaction, pin preservation, monotonic boundary (guard decision and folding delegate to orrery-memory's shared context subsystem) |
| HITL | `hitl/*` | approval gate (SPI, memory / redis implementations), suspended-state contract, decision injection as synthetic receipts, decision-store fallback |
| Checkpoint persistence | `checkpoint/*` | CheckpointStore SPI + InMemory / Redis implementations + three durability tiers (SYNC/ASYNC/EXIT) |
| Cross-run memory | `bridge/memory/*` | MemoryRunBridge (runtime writes into orrery-memory), MemoryCheckpointStore (memory-backed checkpoint store) |
| Run facade | `runner/AgentRunner` | Unified run lifecycle: terminal events, suspension persistence, checkpoint recovery, approval resume |
| LLM adaptation | `llm/*` + `bridge/cyai/AiFacadeLlmInvoker` | retry, truncation continuation, structured output (JSON/Schema + parse retry), cy-common-ai production bridge |
| Event system | `event/*` | 13 frozen event types, TEXT_DELTA streaming, `bridge/protocol/AgentEventBridge` bridging Agent Event Protocol v1 |
| Observability | `observe/MicrometerRuntimeObserver` | Event-driven Micrometer instrumentation (runs/tools/fuses/tokens) |
| Intent routing | `intent/IntentRouter` | intent key → strategy dispatch (engine selection) |
| Plan ledger | `plan/PlanLedger` | explicit plan reconciliation and advancement, early convergence on all-terminal |

> ⚠️ **Approval barrier contract (execute first, judge after)**: approval / clarification
> decisions take the tool's **execution result** as input — the call that triggers approval
> has already really executed before the verdict; the barrier only blocks sibling calls in
> the same round (deferred pending approval, deterministically replayed after approval).
> An approval-triggering tool must answer with a side-effect-free receipt (dry-run form);
> the real persistence happens at replay — only then does "reject" equal "never executed".

**Cross-run memory prerequisites** (`bridge/memory/*` — an optional capability each
business host decides on):

- `MemoryRunBridge`: prefer the full constructor (passing `MemoryManager`) — it validates
  the layers required for consolidation/summarization against `capabilities()` at
  construction and logs one-time WARNs on gaps; at runtime it acts only on layers the
  business actually enabled, skipping actions whose target layer is off (private user
  consolidation never falls back into a shared layer).
- `MemoryCheckpointStore` fails fast at construction unless the WORKING layer is enabled
  (50501); the **WORKING layer's default TTL is 1 hour** — for multi-day approval
  suspension recovery raise `cy.orrery.memory.layers.working.ttl-seconds` to at least
  the suspension horizon (aligned with the Redis store's suspended-ttl semantics,
  default 7 days).

## Getting Started

### Maven dependency

```xml
<dependency>
    <groupId>com.cy.orrery</groupId>
    <artifactId>orrery-agent-runtime</artifactId>
</dependency>
```

Adding this module transitively brings every production-essential dependency:

| Transitive dependency | Purpose |
|---|---|
| `cy-common-ai` | production LLM adapter `AiFacadeLlmInvoker` (reactor arrives via its webflux) |
| `orrery-protocol` | AgentEvent wire-protocol bridge (ecosystem line-protocol standard) |
| `orrery-memory` | cross-run memory bridge + shared context subsystem (context guard/folding delegate to it) |
| `redisson` | Redis checkpoint store / Redis approval gate (multi-replica deployments) |

The only opt-in dependency is Micrometer observability (without it the instrumentation
is not assembled; everything else is unaffected):

```xml
<dependency>  <!-- optional: Micrometer observability -->
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

### Minimal ReAct agent (20 core lines)

```java
// 1. Domain adaptation: implement two SPIs
ToolInvoker tools = (callId, name, args, ctx) -> myTools.execute(name, args);
LlmInvoker llm = new AiFacadeLlmInvoker(aiFacade, myToolSpecs);

// 2. Assemble the engine (stateless, reusable singleton)
LoopToolRoundEngine toolRound = new LoopToolRoundEngine(tools, myInspector, objectMapper,
        Executors.newVirtualThreadPerTaskExecutor());  // read-only parallelism
ReactLoopEngine engine = new ReactLoopEngine(toolRound);

// 3. Run (AgentRunner-managed: terminal events + checkpoints + suspension recovery)
AgentRunContext ctx = runner.startContext(runId, List.of(LoopMessage.user(userInput)));
LoopOutcome outcome = runner.runReact(engine, ctx,
        LoopRuntimeSpec.of(LoopConfig.defaults(), llm, myDomainSpec, contextGuard, myHooks));

// 4. Suspended for approval? Persistence already happened; resume when the decision arrives
if (outcome.stop() == AgentStop.NEEDS_APPROVAL) {
    // ...at any time (even after a process restart):
    AgentRunContext restored = runner.restoreContext(runId).orElseThrow();
    outcome = runner.awaitApprovalsAndResume(engine, restored, spec, 60_000);
}
```

### Spring Boot auto-configuration

Auto-registered with the dependency (all `@ConditionalOnMissingBean`, overridable):

| Bean | Default | Override |
|---|---|---|
| `CancellationRegistry` | in-process | custom bean |
| `ApprovalGate` | in-process / Redis (`cy.orrery.agent-runtime.approval.gate=redis`, needs RedissonClient) | custom bean |
| `ApprovalDecisionStore` | in-process (decision-before-wait fallback) | shared-store implementation |
| `ContextGuard` | config-driven compaction | custom bean |
| `PipelineEngine` | singleton | — |
| `CheckpointStore` | memory / Redis (`cy.orrery.agent-runtime.checkpoint.store=redis`) | custom bean |
| `CheckpointManager` / `AgentRunner` / `HitlCoordinator` / `RuntimeEventBusFactory` | automatic | — |
| `MicrometerRuntimeObserver` | auto-mounted when a MeterRegistry exists | — |
| `AgentEventBridge` | auto-mounted when an AgentEventPublisher bean exists | — |

Engine instances (`ReactLoopEngine` / `LoopToolRoundEngine` / `GuardedToolInvoker` /
LLM decorators) depend on host domain SPIs and are constructed by the host assembly
layer; the auto-configuration does not register them.

### SPI extension points (host integration surface)

| SPI | Responsibility | Required? |
|---|---|---|
| `LlmInvoker` | Normalized LLM calls (streaming TEXT_DELTA, tokens, failure semantics) | yes (or use `AiFacadeLlmInvoker`) |
| `ToolInvoker` | Tool execution boundary (three-way errors: self-correctable / fatal / partial failure) | yes |
| `LoopDomainSpec` | no-tool-round handling, state signature, terminal settlement, fuse messages | yes (most methods defaulted) |
| `ToolRoundHooks` | business-action collection, approval-suspension emission, receipt text | no (NO_OP available) |
| `ToolCallInspector` | tool result → approval/clarification verdict | no (NoOp default) |
| `StageHandler` | stage semantics for the stage chain | when using the chain |
| `ApprovalGate` | approval waiting gate (multi-replica needs a distributed implementation) | no (in-memory default) |
| `CheckpointStore` | checkpoint storage | no (in-memory default; Redis for multi-replica) |
| `CheckpointSerializer.registerAttributeType` | strongly-typed round-trips of host attributes | when custom attributes exist |
| `ContextGuard` / `MessagePinner` | context guard and pinning | no (default implementations) |
| `AgentEventPublisher` | AgentEvent delivery channel (SSE/Redis/MQ) | when using the wire bridge |

## Configuration

All keys live under `cy.orrery.agent-runtime.*`, all defaulted, zero-config usable:

```yaml
cy:
  orrery:
    agent-runtime:
      loop:
        max-rounds: 25                      # ReAct max rounds
        max-consecutive-failed-rounds: 3    # degeneration fuse (consecutive tool-failed rounds)
        max-consecutive-idle-rounds: 3      # convergence fuse (consecutive zero-progress rounds)
      context:
        window-limit: 32000                 # DEPRECATED — window thresholds moved to cy.orrery.memory.context.*; this block only applies as a fallback when cy.orrery.memory.enabled=false
      pipeline:
        max-transitions: 50                 # stage-chain transition fuse
        max-rollbacks: 5                    # stage-chain rollback budget
      tool:
        exec-pool-size: 8                   # legacy (tool execution moved to per-task virtual threads)
        timeout-millis: 30000               # per-tool timeout (hard cancel)
        arg-fuse-threshold: 2               # argument-fingerprint fuse (≤0 disables)
        failure-streak-threshold: 3         # per-tool failure-streak escalation (≤0 disables)
      llm:
        retry-max-retries: 2                # transient-failure retries (0 disables)
        retry-backoff-base-millis: 800      # exponential backoff base
        truncation-max-continuations: 2     # truncation auto-continuation rounds (≥1)
      checkpoint:
        enabled: true                       # false: store / manager / run facade not assembled
        store: memory                       # memory | redis (multi-replica shared)
        durability: ASYNC                   # SYNC | ASYNC | EXIT
        key-prefix: "orrery:agent-runtime:checkpoint:"
        ttl-millis: 86400000
        suspended-ttl-millis: 604800000     # suspended checkpoints (approvals) — must outlive normal TTL
      approval:
        gate: memory                        # memory | redis (cross-instance wake-up)
        decision-ttl-millis: 604800000      # aligned with the suspension TTL
      bridge:
        agent-id: my-agent                  # Agent ID reported to the registry
        agent-event-enabled: true           # AgentEvent wire bridge switch
```

> `tool.*` and `llm.*` do not drive auto-configuration; the host reads them when
> constructing `GuardedToolInvoker` / LLM decorators (engine instances are host-assembled).

## Compatibility & Operations

**Compatibility**: versions follow the orrery ecosystem (1.0.0). Public SPIs
(`LlmInvoker` / `ToolInvoker` / `LoopDomainSpec` / `StageHandler` / `ApprovalGate` /
`ApprovalDecisionStore` / `CheckpointStore` / `ContextGuard` / `MessagePinner` /
`AgentEventPublisher`) and the `RuntimeEventType` enum stay compatible — breaking
changes require review and ride a major ecosystem version. Config keys are only ever
added, never renamed. Deprecations keep a `@Deprecated` marker for one minor cycle
(currently deprecated: `cy.orrery.agent-runtime.context.*` — window thresholds now live
under `cy.orrery.memory.context.*`; the block only applies as a fallback when
`cy.orrery.memory.enabled=false`).

**Alerting suggestions**:

| Signal | Meaning |
|---|---|
| `orrery.agent.checkpoint.save.failures` rising | Async checkpoint persistence failures (serialization errors / store unavailable) |
| Log "记忆巩固任务异常" rate rising | Consolidation channel failures (LLM parse errors etc.; the round is dropped) |
| Log "巩固通道所需层均未开启" / "SESSION 层未开启" | MemoryRunBridge vs memory layer misconfiguration (one-time startup warnings) |

## License

Follows the Orrery ecosystem (internal artifact, not independently open-sourced yet;
an actual LICENSE and scm metadata must be added before any open-source release).
