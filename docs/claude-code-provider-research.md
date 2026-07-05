# Claude Code Provider Research

## Goal

Add a Gromozeka AI provider that uses the local Claude Code CLI credentials instead of an Anthropic API key.

## Current Gromozeka State

- `AiConnection.Kind.CLAUDE_CODE` and `AiConnection.ClaudeCode` already exist in the domain.
- Settings UI already knows how to display/edit the connection shape.
- No `AiRuntimeBackend` implementation currently handles `CLAUDE_CODE`.

## Official Integration Surface

Claude Agent SDK is officially available for Python and TypeScript, not JVM. The SDK exposes the Claude Code agent loop, tools, context management, sessions, hooks, MCP, and structured outputs. The TypeScript SDK bundles a native Claude Code binary, but can also be pointed at an installed `claude` executable.

For JVM, the practical options are:

1. Call `claude` directly with `ProcessBuilder`.
2. Add a Node/Python sidecar that wraps the official Agent SDK.

The direct CLI wrapper is the best first implementation for Gromozeka because it keeps the server pure JVM and covers normal provider calls. A sidecar is still useful later if we need long-lived SDK sessions, SDK custom tools, or advanced interruption semantics.

## Verified Local CLI

Verified on this machine:

```text
claude 2.1.198
authMethod=claude.ai
subscriptionType=pro
```

Minimal non-interactive provider call works:

```bash
claude -p \
  --safe-mode \
  --tools "" \
  --disable-slash-commands \
  --setting-sources "" \
  --no-session-persistence \
  --output-format json \
  --system-prompt-file /path/to/system-prompt.txt \
  --model haiku \
  "Return exactly OK."
```

The same shape works with `--output-format stream-json --verbose`.

Structured output works:

```bash
claude -p \
  --safe-mode \
  --tools "" \
  --disable-slash-commands \
  --setting-sources "" \
  --no-session-persistence \
  --output-format json \
  --json-schema '{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}' \
  --model haiku \
  "Return answer OK."
```

The result message includes both `result` as JSON text and `structured_output` as parsed JSON.

Streaming input requires `stream-json` output and `--verbose`:

```bash
printf '%s\n' '{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Return exactly OK."}]},"parent_tool_use_id":null,"session_id":"11111111-1111-4111-8111-111111111111"}' \
  | claude -p \
      --safe-mode \
      --tools "" \
      --disable-slash-commands \
      --setting-sources "" \
      --no-session-persistence \
      --input-format stream-json \
      --output-format stream-json \
      --verbose \
      --system-prompt-file /path/to/system-prompt.txt \
      --model haiku
```

## Important Flags

- `--tools ""` removes built-in tools from the model context. Do not rely on `allowedTools` for restriction.
- `--disable-slash-commands` removes slash commands/skills from the session.
- `--setting-sources ""` prevents user/project/local settings from being loaded.
- `--safe-mode` disables customizations while keeping authentication, built-in tools, model selection, and permissions. It is useful for a sterile provider call, but it is not a replacement for explicit flags. Keep the explicit flags because they document the intended request shape.
- `--no-session-persistence` avoids writing resumable Claude sessions for one-shot provider calls.
- `--system-prompt-file` works and should be preferred over huge command-line arguments.
- `--json-schema` is the right path for `AiResponseFormat.JsonSchema` in print mode.

Do not use `--bare` for Claude subscription OAuth mode unless this changes upstream. Current help says bare mode does not read OAuth/keychain credentials and expects `ANTHROPIC_API_KEY` or an `apiKeyHelper`.

## Hindsight Findings

Hindsight has two Claude integrations:

- Claude Code plugin: hooks into Claude Code itself, auto-recalls memory on `UserPromptSubmit`, auto-retains on `Stop`, exposes MCP tools.
- Claude Code LLM provider: uses Python Claude Agent SDK as an LLM provider backed by Claude Code auth.

The provider does not call the CLI manually. It imports `claude_agent_sdk`, which spawns the CLI. Useful lessons:

- Isolate spawned CLI config from user Claude Code plugins/hooks. Hindsight sets a temporary `CLAUDE_CONFIG_DIR`.
- Be careful with `CLAUDE_SECURESTORAGE_CONFIG_DIR`; Hindsight comments say their usage requires newer bundled CLI/SDK behavior.
- SDK `query()` is fine for simple text calls.
- SDK client is needed for custom SDK MCP tools.
- Exact token usage may be unavailable or inconsistent; Hindsight estimates in some paths.

## Recommended First Implementation

Implement `ClaudeCodeCliRuntimeBackend` in `infrastructure-ai`:

- `supports(CLAUDE_CODE)`.
- Build temp files for system prompt and optional stdin payload.
- Start `claude` via `ProcessBuilder`, never shell string interpolation.
- Use `Dispatchers.IO`.
- Parse JSON/stream-json result lines with kotlinx.serialization.
- Return `AiRuntimeResponse` with assistant text, tool calls if present later, usage metadata if available.
- Fail fast on missing CLI, unsupported version, auth failure, non-zero exit, and `is_error=true`.
- For provider-mode, always disable Claude Code tools and user customizations unless Gromozeka explicitly opts into agent-tool mode later.

Initial stateless call shape:

```text
claude -p
  --safe-mode
  --tools ""
  --disable-slash-commands
  --setting-sources ""
  --no-session-persistence
  --output-format json
  --system-prompt-file <tmp>
  --model <providerModelId>
```

Use `--json-schema <schema>` when `AiRuntimeOptions.responseFormat` is `JsonSchema`.

## Tools

There are two different tool-handling modes, and they should not be mixed accidentally.

### Gromozeka-owned tool loop

This matches the existing `AiRuntimeRequest` contract: the provider returns tool calls, Gromozeka executes tools, stores tool results, and calls the model again.

Claude Code CLI does not expose API-style custom function calling directly in `--print` mode. The clean first implementation should therefore support no tools or use structured output to ask for a Gromozeka-specific tool-call JSON shape. This keeps the conversation engine authoritative but is less native than Anthropic/OpenAI tool calling.

### Claude-Code-owned tool loop

Claude Code can call tools through built-in tools and MCP servers. In this mode Gromozeka can expose its tools through a local MCP bridge and pass that bridge with `--mcp-config`.

This lets Claude Code run its own agent loop, but tool execution happens inside the Claude Code process tree from Gromozeka's point of view. Gromozeka would need to parse stream-json events and/or MCP bridge logs to mirror tool calls/results into the conversation UI. This is a separate capability, closer to "Claude Code agent mode" than a normal LLM provider.

Other integrations follow this pattern:

- Hindsight exposes memory as MCP tools for Claude Code/Agent SDK. For normal LLM calls it sets `max_turns=1` and `allowed_tools=[]`. For forced-tool calls it exposes an MCP server, allowlists the selected MCP tool, and uses `max_turns=2` so Claude can call the tool and then produce a final response.
- `ai-sdk-provider-claude-code` says AI SDK tools cannot be auto-bridged at provider level because execute functions are not available to the provider. Its recommended path is an explicit in-process MCP bridge plus `allowedTools`.

Tool-control rules:

- `allowedTools` is the right control surface when we want "only these tools".
- Use only `mcp__gromozeka__...` names in `allowedTools` to exclude built-in tools like `Bash`, `Read`, `Write`, `Edit`.
- `allowedTools=[]` means no tools for plain LLM calls.
- Do not mix `allowedTools` and `disallowedTools`; allowlist is simpler and safer.
- `max_turns=1` is good for LLM-only calls. For tool mode, it is usually too strict: Claude may spend the only turn on the tool call and stop with `max_turns` before producing the final assistant answer. Tool mode should use a small fixed cap such as `max_turns=2` for one tool round trip, or a deliberately higher cap only for explicitly agentic flows.

Recommendation:

1. Implement `CLAUDE_CODE` provider as LLM-only first: `allowedTools=[]`, no MCP, `max_turns=1`.
2. Add explicit Claude Code tool mode later: expose Gromozeka tools through an MCP bridge, pass only that bridge, set `allowedTools` to our MCP tool names, and set a small `max_turns` cap.
3. Mirror provider-executed MCP tool calls/results into Gromozeka UI/history from stream events, because Gromozeka is no longer the direct executor in this mode.

## Cache And Sessions

Claude Code prompt caching is server-side prefix caching. It works best when consecutive requests have the same model, effort, system prompt, tool definitions, settings, cwd-derived context, and previous conversation prefix.

Official Claude Code docs say Claude Code manages prompt caching automatically and re-sends the full context every turn. The cache matches the exact request prefix; model and effort are part of the cache key. On Claude subscription auth, Claude Code requests a one-hour TTL automatically. Cache health is visible in `cache_creation_input_tokens` and `cache_read_input_tokens`.

Local CLI experiments on this machine:

- A repeated large `claude -p --no-session-persistence` prompt can hit cache across separate processes: first call wrote `cache_creation_input_tokens`, second identical call read the same amount.
- A prompt with the same large text prefix but a changed suffix did not read cache. This matches the API docs warning that automatic breakpoints can land on the varying block; changing the final block can miss.
- A growing-history simulation via full stdin payload also did not read the first request's cache in the next request.
- A session-backed flow did work: first call returned `session_id`, later `claude -p --resume <session_id>` calls with the same system prompt read cached tokens.

So a pure one-shot stateless provider is safe, but it is not the best final shape for multi-turn conversations. It only gets reliable cache wins when the whole prompt is repeated or when Claude Code/API happens to place a useful breakpoint. For normal chat, use Claude Code sessions as an optimization while keeping Gromozeka's conversation as the source of truth:

- Store the returned `session_id` per Gromozeka conversation and model configuration.
- Resume with `--resume <session_id>` or `--continue` for subsequent calls.
- Send only the new user turn when resuming, not the full Gromozeka history.
- Do not use `--no-session-persistence` in session-backed mode.
- Keep `cwd`, model, effort, tools, system prompt, settings sources, and MCP config stable.
- Treat cache/session state as an optimization. Gromozeka conversation history remains canonical and can rebuild a Claude Code session after cache/session loss.

This suggests two modes:

1. `session-backed`: preferred default for chat. Stores Claude Code session IDs and resumes them for cache continuity.
2. `stateless`: useful for smoke tests, one-off requests, fallback diagnostics, and cases where writing Claude session state is undesirable. Lower cache benefit for growing conversations.

## Open Decisions

- Whether to represent full Gromozeka message history as text transcript, stdin Anthropic-style JSON, or stream-json SDK messages. Direct stdin JSON appears to work, but it may be less documented than stream-json SDK events.
- Whether `CLAUDE_CODE` should be a simple LLM provider only, or later a separate "Claude Code agent mode" with tools/file access. These should be separate capabilities/configurations.
- Whether to auto-run a CLI smoke test in settings UI or keep it server-side fail-fast only.
