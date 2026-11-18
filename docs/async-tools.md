# Durable asynchronous tool invocations

`_grz.async: true` is an explicit Harness option for a model-owned conversation
tool call. Omitting it, or using `false`, preserves ordinary execution.

```json
{"path":"file.txt","_grz":{"workspace_mount_id":"exact-mount","async":true}}
```

## Receipt and completion

The source tool result is an `ACCEPTED` receipt with `operation_id`. This reports
that the invocation was durably saved, **not** that its action succeeded or even
started. It closes the original tool-call/result pair.

Execution waits for the receipt to exist in the original persisted history
thread. A completion is a Runtime-authored `RUNTIME_NOTIFICATION` with the same
operation ID and related original tool-call ID. It is never another tool result
for that original call. Native provider projection lowers the notification into
an explicitly labelled, untrusted user-data envelope after attachment
materialization; it is not a system/developer instruction or a user permission.

The invocation finishes when its tool callback returns. A tool that itself
returns a managed-command, monitor, memory-run or other pending receipt retains
that underlying resource's existing lifecycle. Generic async does not implicitly
poll or await the nested resource.

Use `grz_async_tool_get(operation_id)` to inspect the durable status/outcome and
`grz_async_tool_cancel(operation_id)` to request cancellation. Neither retrieving
nor cancelling an operation is permission to repeat its original action.

## Identity, authorization and unsupported contracts

The authenticated actor, project, conversation, source thread, agent, channel,
source task/call, exact tool revision, Worker/mount/root and slot lease are captured
by Server. `_grz` selectors cannot supply or override that authority. Admission
and execution retain resource/tool authorization and approval. Approval reuse is
an internal proof bound to the exact callback fingerprint and call ID, not an
argument-controlled boolean.

Deduplication uses the original conversation/task/call identity. Thread and actor
are immutable captured values, not additional ways to create a second operation
for the same source call. A rebind or changed arguments fails explicitly.

Readonly origin is retained, including inherited readonly collaboration, without
turning descriptive `readOnlyHint` annotations into an authorization policy.
Changing bindings does not select a newer checkout or lease for queued work.
If the original destination is no longer eligible, the outcome remains in the
invocation store for authorized inspection; it is not silently posted to another
branch or agent.

The current stateless external MCP/SDK HTTP entry point creates a fresh call ID
and has no durable source model pair/ACK identity. It explicitly rejects async
with `async_origin_required` before effects. This is an entry-point constraint,
not an allowlist of tool names. SDK `await`/`gather` and the managed Python process
lifecycle remain separate features.

A callback may declare an inline-only reason for an objectively invocation-bound
effect. Secret revelation is such a contract: it injects a secret into the
immediate next model request. Detaching it could expose it at another boundary,
so async is rejected explicitly rather than being executed inline as a fallback.
Provider-hosted native tools are not Harness callbacks and do not acquire this
flag merely because their provider calls them tools.

## Recovery and cancellation

Invocations and outcomes use the existing Server secret cipher. Operation state
separates `ACCEPTED`, `ACKNOWLEDGED`, `CLAIMED`, `RUNNING` and terminal outcomes.
Claim recovery is fenced by the exact executor session, durable claim generation
and lease. The atomic claim update returns its own grant; delayed preparation,
secret resolution and cancellation cannot finalize or cancel a replacement
claim, including a later claim by the same Server session. Start, renewal and
terminal updates compare that generation. Worker-child creation fences further
reclaim, and a stale pre-child observation cannot finalize a now-linked parent. A lost running
Server callback becomes `OUTCOME_UNKNOWN`; recovery does not invoke it again.

Worker execution reuses WorkerRequest delivery and its durable Worker journal.
Its child request ID is the operation ID. Creation records a parent tombstone in
the same transaction: a missing once-created child cannot be recreated after
retention/reconnect. Canonical responses are projected into the operation before
the WorkerRequest projection/retention acknowledgement advances. A late success
cannot replace an already committed unknown outcome.

Cancellation before execution can report `CANCELLED`. Once effects may have
started, cancellation is best-effort and does not imply rollback. Server running
callback loss/cancellation without a definitive result is unknown, not proof of
failure without effects.

Turn completion/STOP do not become implicit rollback or cancellation of accepted
background work. The existing unrestricted conversation INTERRUPT also requests
cancellation of async operations; an expected-turn-only interrupt does not gain
that broader meaning. Explicit per-operation cancellation remains available.

Background notification batching and its delivery acknowledgement reuse the
existing coordinator transaction and continuation. Existing SYSTEM notifications
and managed command/monitor synthetic-pair paths remain unchanged where generic
async does not own their result.

## Compatibility and integration with typed events

Worker Gateway protocol **35** is required for the new invocation metadata,
revision-bound approval proof and serialized runtime notification. Upgrade Server,
Workers and clients to compatible builds. This document does not authorize a
release or deployment.

This feature includes only the RuntimeNotification subset of the typed-events
work at commit `179ff1730d28d5925e21d88425b00b70b8f0d74a` (PR #202). It preserves
the agreed type/role/fields and late provider-envelope model. It does not include
PEER, PROMPT, VISUAL_UPDATED, immutable turn-prompt binding or migration of legacy
SYSTEM records. The async storage migrations follow the actual main migration
sequence (`V90` after main's V88/V89, then `V91` for durable claim generations).
V91 carries forward valid V90 parent/child claims without changing canonical
Worker request bytes; inconsistent legacy ownership fails explicitly instead of
being repaired by guessing. Claim generations and the child claim reference are
Server-private storage metadata; the Worker wire protocol remains 35.

When integrating the complete typed-events change, reconcile the shared
RuntimeNotification/Runtime author, output artifact support and common service
projection; retain the added `TOOL_COMPLETED` kind. Extend that same model instead
of introducing a second notification format. Renumber the older typed-events
migration against the then-current main. Separately preserve the existing
maintenance boundary: ApplyPreparedCompaction must validate scope without
requiring or creating a new agent-turn PROMPT binding.
