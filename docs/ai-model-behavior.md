# Explicit model request behavior

`AiModelSpec.behavior` contains request rules for one exact model route. Copy it
with the spec when registering a gateway model, then adjust it to that route's
actual capabilities. Set the spec provider to the connection kind's provider
(`CUSTOM` for OpenAI-compatible Chat Completions). The connection still selects Chat Completions, Responses,
or Anthropic Messages. Neither model names nor suffixes select these rules.
The model spec editor and existing catalog CRUD expose the same typed settings.

| Field | Meaning |
| --- | --- |
| `chatCompletionsTokenLimit` | `MAX_COMPLETION_TOKENS` (default), `MAX_TOKENS`, or `OMIT`. Sends exactly one field or neither, including when a call has an output limit. Responses and Messages retain their native token-limit fields. |
| `chatCompletionsReasoning` | `REASONING_EFFORT` (default) or `ANTHROPIC_THINKING`. The latter sends `thinking` and, when requested, `output_config.effort` within the existing Chat Completions request. It does not change the endpoint. |
| `defaultReasoningMode` | Optional model-specific mode when neither the call nor model configuration supplies a mode. Explicit `DISABLED` is never replaced by this default. |
| `minimumToolReasoningEffort` | Optional minimum when tools are enabled. Missing, insufficient or disabled reasoning is rejected before HTTP, not silently enabled or raised. `toolChoice=None` removes this tool-only requirement. |
| `maximumDisabledReasoningEffort` | Optional highest effort compatible with disabled thinking. Anthropic effort also controls visible output and tool use, independently of thinking. |
| `supportsForcedToolChoice` | Defaults to true. False rejects required-any and required-named tool choices, but keeps auto/none and ordinary function tools available. |
| `anthropicThinkingPrefixDrop` | Explicit opt-in to adaptive `thinking.block_binding.prefix_mismatch_behavior=drop_block` and the `thinking-binding-controls-2026-08-01` beta header. Applies to native Messages and the Anthropic-thinking Chat encoding, not Responses. |

Nonempty `reasoning.modes`, `reasoning.efforts` and `reasoning.displays` constrain
explicitly requested values. Empty/absent declarations remain unknown; they do
not manufacture capabilities or model limits. A configured default mode must be
allowed, and a tool minimum must be reachable by the declared efforts.

## Reasoning semantics

For OpenAI effort encoding, `DISABLED` sends `none`, even when the saved effort
is nonempty. Omitting the field does **not** mean off. A route that does not
support off must omit `DISABLED` from its declared supported modes. A rejected
request never triggers a switch of model, encoding, or effort.

`ADAPTIVE` with OpenAI effort encoding requires an explicit effort; the adapter
does not choose an arbitrary level. `TOKEN_BUDGET` is rejected by that encoding.
Efforts are transmitted exactly, including `max` rather than silently reducing
it to `xhigh`. Chat Completions effort encoding has no reasoning-display control;
set no display there. Responses maps visible reasoning to its summary control
and does not request a summary when reasoning is disabled.

Anthropic thinking encodes disabled/adaptive/enabled explicitly. A manual budget
requires `TOKEN_BUDGET`; the existing default budget is 16,000 tokens when that
mode is selected without a budget. Its output-token limit must be larger. Effort
is independent and can remain present with disabled thinking if the route allows
that combination. There is no automatic promotion from off to the minimum
required for tools.

Example for a route accepting Anthropic thinking through Chat Completions:

```json
{
  "chatCompletionsTokenLimit": "MAX_TOKENS",
  "chatCompletionsReasoning": "ANTHROPIC_THINKING"
}
```

This example does not assert the route's supported modes, context window, or
native structured-output capability. Those must be configured separately.
TrueFoundry documents the additional `X-TFY-STRICT-OPENAI: false` header for its
Claude thinking extension. Header/auth/network configuration is separate from
model behavior; selecting this encoding does not silently add a gateway header.
Gateway versions can expose different response shapes, so the target installation
still requires its own reasoning/tool-turn acceptance test.

## Upgrade and persistence

V79 migrates only the existing native Anthropic `claude-opus-5` and
`claude-opus-5-5` specs that lack a behavior field. It preserves the old effective
mode restrictions, independent effort restriction, forced-tool restriction and
prefix-drop setting as explicit data, leaving limits and other fields intact.
Already configured behavior, gateway IDs and other providers are not rewritten.
New installations receive the same settings from the seed catalog. There is no
runtime model-name fallback.

The complete resolved spec now reaches the SDK backend on Server and Worker.
Worker Gateway protocol 17 requires a coordinated Server/Worker upgrade because
its strict catalog/request codecs include the new serialized field. The ordinary
UI protocol remains unchanged. Do not edit the catalog using an older client
that cannot preserve the new settings.

## References

- [TrueFoundry Claude thinking extension](https://www.truefoundry.com/docs/ai-gateway/reasoning-models-claude-only)
- [Anthropic thinking modes](https://platform.claude.com/docs/en/build-with-claude/thinking)
- [Anthropic effort](https://platform.claude.com/docs/en/build-with-claude/effort)
- [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)
