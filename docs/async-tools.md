# Durable asynchronous tool invocations

`_grz.async: true` is an explicit Harness option for a model-owned conversation
tool call. Omitting it, or using `false`, preserves ordinary execution.

```json
{"path":"file.txt","_grz":{"workspace_mount_id":"exact-mount","async":true}}
```

## Receipt and completion

The source tool result is an `ACCEPTED` receipt with `operation_id`. This reports
that the invocation was durably saved, **not** that its action succeeded or even
started. Its pairing ID is the original model call ID, and it closes that pair.
Execution waits for this receipt in the original persisted history thread.

The final outcome is stored in canonical history as two ordinary messages:

1. An assistant-role synthetic `ToolCall` with a new, stable pairing ID.
2. A user-role `ToolResult` using that new ID and the original callback output.

Both messages are Runtime-authored and marked `ASYNC_TOOL_COMPLETION`. Their
metadata preserves the operation ID and original source call/agent. The call uses
the captured model tool name and captured arguments, including secret references,
not newly resolved credentials or arguments reconstructed from the current
catalog. A Runtime observation explicitly tells the model that this is a record
of an earlier invocation, not a new execution. The synthetic call is never sent
to the executor; callback execution, Worker RPC and approval proof remain bound
to the original call.

The final `ToolResult` follows the ordinary result path, preserving text, media,
error/state and execution-tool identity. Structured-only callbacks use the same
normal fallback as synchronous calls; completion does not add another result
envelope or structured-output summary. Existing artifact persistence is shared
with synchronous results, rather than a special async file transport.

The two call IDs must differ. Each pair must match internally. New completion
IDs are portable from creation and are not repaired using legacy normalization.
The pair is appended atomically, and an existing ID alone is not proof of
previous delivery: replay validates both complete canonical bodies after output
artifact persistence. A body/provenance collision fails before delivery ACK.

The invocation finishes when its tool callback returns. A tool that itself
returns a managed-command, monitor, memory-run or other pending receipt retains
that underlying resource's existing lifecycle. For example, an async invocation
may succeed with a `WORKING` command receipt while the command remains active.
Generic async does not implicitly poll or await the nested resource.

Use `grz_async_tool_get(operation_id)` to inspect durable status/outcome and
`grz_async_tool_cancel(operation_id)` to request cancellation. Neither retrieving
nor cancelling an operation permits repeating its original action.

## Identity, authorization and unsupported contracts

The authenticated actor, project, conversation, source thread, agent, channel,
source task/call, exact tool revision, Worker/mount/root and slot lease are captured
by Server. `_grz` selectors cannot supply or override that authority. Admission
and execution retain resource/tool authorization and approval. Approval reuse is
an internal proof bound to the exact callback fingerprint and original call ID,
not an argument-controlled boolean or the synthetic completion call.

Deduplication uses the original conversation/task/call identity. Thread and actor
are immutable captured values, not additional ways to create a second operation
for the same source call. A rebind or changed arguments fails explicitly.

Readonly origin is retained, including inherited readonly collaboration, without
turning descriptive `readOnlyHint` annotations into an authorization policy.
Changing bindings does not select a newer checkout or lease for queued work.
If the original destination is no longer eligible, the outcome remains in the
invocation store for authorized inspection; it is not silently posted to another
branch or agent.

Clients cannot submit Runtime authorship, async-completion provenance or typed
Runtime observations. Both stored completion messages are protected from client
history edits. A synthetic metadata marker by itself is not trusted authorship.

The current stateless external MCP/SDK HTTP entry point creates a fresh call ID
and has no durable source model pair/ACK identity. It explicitly rejects async
with `async_origin_required` before effects. This is an entry-point constraint,
not an allowlist of tool names. SDK `await`/`gather` and the managed Python process
lifecycle remain separate features.

## Recovery and cancellation

Invocations and outcomes use the existing Server secret cipher. Operation state
separates `ACCEPTED`, `ACKNOWLEDGED`, `CLAIMED`, `RUNNING` and terminal outcomes.
Claim recovery is fenced by the exact executor session, durable claim generation
and lease. The atomic claim update returns its own grant; delayed preparation,
secret resolution and cancellation cannot finalize or cancel a replacement claim,
including a later claim by the same Server session. Start, renewal and terminal
updates compare that generation. Worker-child creation fences further reclaim,
and a stale pre-child observation cannot finalize a now-linked parent. A lost
running Server callback becomes `OUTCOME_UNKNOWN`; recovery never invokes it again.

Worker execution reuses WorkerRequest delivery and its durable Worker journal.
Its child request ID is the operation ID. Creation records a parent tombstone in
the same transaction: a missing once-created child cannot be recreated after
retention/reconnect. Canonical responses are copied into the operation before
WorkerRequest projection/retention ACK advances. A late success cannot replace an
already committed unknown outcome.

Cancellation before execution can report `CANCELLED`. Once effects may have
started, cancellation is best-effort and does not imply rollback. An unknown
outcome explicitly warns that effects may have occurred and the action must not
be repeated automatically. Successful callback bodies do not receive a status or
warning prefix.

Turn completion/STOP do not become implicit rollback or cancellation of accepted
background work. The existing unrestricted conversation INTERRUPT also requests
cancellation of async operations; an expected-turn-only interrupt does not gain
that broader meaning. Explicit per-operation cancellation remains available.

Completion batching and its delivery ACK reuse the existing coordinator
transaction and continuation. Existing SYSTEM messages and command/monitor/memory
synthetic pairs retain their established semantics.

## Clean schema and coordinated upgrade

This implementation deliberately does not read or migrate the former generic
async Runtime-notification format. Installing it over an instance that ran that
format requires the agreed restoration of a backup taken **before 6.6.0**, or a
fresh database. Do not bypass Flyway validation or mark an incompatible schema as
repaired to avoid that restoration.

The pre-existing migrations through V89 remain intact. V89 types provenance of
real command/monitor/memory messages; it is not a migration of generic async
notifications. V90 creates async storage with claim generations and both fencing
constraints from the outset. There is no upgrade step for old async rows.

Worker Gateway protocol **36** is required because the serialized history variants
changed. Upgrade Server, Workers and clients to compatible builds. Schema tests
and native request serialization tests are distinct from live-provider acceptance,
including provider-specific reasoning/signature constraints. This document does
not itself authorize release, restoration or deployment.

The separate typed-events work in PR #202 is not included. A future integration
must respect this canonical tool-pair contract instead of reinstating a parallel
async notification format. Its immutable agent-turn prompt binding must also
remain separate from the existing `ApplyPreparedCompaction` maintenance boundary.
