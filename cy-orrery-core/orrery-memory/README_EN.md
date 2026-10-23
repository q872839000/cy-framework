# orrery-memory

Layered memory for Orrery agents: L0 context window management, L1 episodic
cards, L2 long-term semantic memory (vector-only recall), and a consumer-defined
user profile subsystem. Capabilities assemble à la carte; every LLM prompt is
overridable.

See [README.md](README.md) (Chinese, canonical) and
[docs/memory-layered-refactor-design.md](docs/memory-layered-refactor-design.md)
for the full design.

## Layers

```
L0 active context : the conversation window itself (component-managed, Redis)
L1 episodic       : per-session episode cards (Redis 7d + vector index)
L2 long-term      : distilled facts (MySQL body+audit, Milvus vector-only recall)
Profile           : standalone subsystem (MySQL itemized, dimensions defined by
                    the consumer via ProfileDimensionProvider)
```

## Quick start

```yaml
cy.orrery.memory:
  vector-dimension: 1024
  profile:  { enabled: true }    # needs MySQL
  semantic: { enabled: true }    # needs MySQL + Milvus + embedding (fail-fast at startup)
  episodic: { enabled: true }    # needs Redis + Milvus + embedding
```

```java
// declare what to remember (consumer-owned dimensions)
@Bean
ProfileDimensionProvider dimensions() {
    return () -> List.of(new ProfileDimension(
            "move_semantics", "「移动」指令语义偏好",
            "whether 'move' means A) relocating an initial deployment or B) route planning",
            false, 1));
}

// L2 hot-path write and semantic recall
memoryTools.remember(MemoryScope.ofUser(null, "u123"),
        "user prefers staging new units east of the assembly area", "preference", 7, "s1#r1");
List<String> hits = memoryTools.recall("deployment habits", null, 5);
```

## Prompts

Bundled defaults are English (adapted from Claude Code's production prompts);
output language is pinned to the conversation's dominant language. Override at
three levels: Spring bean > `cy.orrery.memory.*.*-prompt` config > bundled
default. The profile-extraction prompt's dimension section is always assembled
from the consumer's `ProfileDimensionProvider` and cannot be overridden.

## License

MIT (see [LICENSE](LICENSE)).
