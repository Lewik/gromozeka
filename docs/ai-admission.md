# AI concurrency groups

AI admission is owned by one Server coordinator, not by individual providers or
Worker processes. Every model configuration references exactly one `AiConcurrencyGroup`
through `concurrencyGroupId`. Group IDs are explicit configuration: matching URLs,
credentials or provider names never implicitly combine configurations.

`default` is permanent and initially allows 4 concurrent operations. New
model configurations use it unless another group is selected. A group limit is 1–64, or
explicit `null` for unlimited. Unlimited operations still pass through admission
and retain the same lifecycle and diagnostics. Optional [HTTP TPM admission](ai-tpm.md) shares this coordinator but has separate
named groups, estimates and feedback. Provider quotas are never auto-discovered.

## Configuration

The AI catalog contains `concurrencyGroups` alongside connections, model specs,
configurations and assignments. Changes use the catalog's optimistic revision.
The settings UI edits groups and a model configuration's group membership. Control MCP has:

- `grz_ai_catalog_get`: groups and model configuration membership in the normal catalog.
- `grz_ai_concurrency_group_upsert`: a complete `group` and `expectedRevision`.
- `grz_ai_concurrency_group_delete`: `groupId` and `expectedRevision`.
- `grz_ai_model_configuration_upsert`: sets `configuration.concurrencyGroupId`.
- `grz_ai_admission_status`: current waiting operations and active leases with
  measurement time; routing metadata and quota balances, not model prompts or responses.

These operations require Server-owner access. The default group cannot be
deleted. Neither can a group referenced by a model configuration or an active durable
lease. A configuration change does not move an already active lease to another
group. New grants resolve current membership; lowering a limit never cancels
active work. Catalog revisions prevent delayed snapshot updates from undoing a
newer admission configuration.

## What owns a place

`AiCallAdmission.execute` acquires permission, executes the adapter block and
finishes in a cancellation-safe `finally`. The block includes actual transport
completion/cleanup. A permit is not owned by a conversation turn, a tool call, an
idle CLI process or a runtime object. Session/cache locks are acquired before
admission so a blocked local session does not consume shared capacity.

SDK HTTP retries acquire separately for each attempt and release before backoff.
Their existing conservative retry policy is outside admission: a queue or lease
failure is not a reason to retry inference or switch transport. Responses bodies,
including streaming bodies, remain charged until consumed or closed. Finite
embeddings, STT/TTS, explicit model discovery and auxiliary LLM calls use the same
coordinator. Local Whisper uses the explicit default group; an embedding cache
hit does not execute AI and needs no admission.

Native Claude Code, Copilot and subscription adapters admit the operations
Gromozeka controls. Internal requests and provider-side retries hidden inside a
CLI/gateway are not individually observable. This is not an exact upstream HTTP
request counter. A future adapter operation must use the same entry point, not
introduce a private semaphore. Nested admission is an explicit error.

Live transcription/capture sessions own a session handle. Their places are
released only after the actual session is stopped/cancelled, not when the command
that started recording returns. This does not broaden supported execution targets
or change compaction, provider replay, or conversation/thread semantics.

## Queue and cancellation

A coroutine actor serializes admission decisions; provider calls and database
I/O do not run inside it. Pending entries can be removed independently. It admits
the oldest currently eligible operation: a saturated group cannot block an older
or newer eligible operation in another group. The waiting queue is bounded at
4096; overflow fails explicitly rather than bypassing admission.

Cancellation or the caller's deadline while waiting prevents provider execution.
A cancelled waiter cannot release another operation's grant. Existing logical
SDK deadlines include queue time, all attempts and backoff. Group changes can
unblock waiting operations; there is no polling loop per waiter.

## Worker ownership and recovery

Workers request admission through authenticated `AI_ADMISSION` Gateway callbacks.
The Server validates the owning dispatched Worker request and its operation type,
and persists the lease before returning a grant. The callback is outside the
generic callback semaphore: waiting admission must not block the completion
callbacks needed to free capacity.

Workers durably journal grants before starting inference. Completion is recorded
before sending `Finish`; its acknowledgement removes the journal entry. If the
control plane is unavailable, only that idempotent completion notification is
retried on reconnect. The model action is never repeated. Invalid protocol,
ownership or journal state fails explicitly rather than selecting another
executor or silently granting local capacity.

A detached Claude microphone capture additionally performs `Accept` after its
grant is journaled. Only then may capture start. The Server validates its session
and connection against the original start command. An accepted background lease
survives that start command's result; an unaccepted grant does not. This handoff
avoids both premature release and a leaked grant whose reply never reached the
Worker.

Only an actual Worker response can retire the request's finite/unaccepted
leases. A Server timeout, synthetic unknown outcome, disconnect, or heartbeat
loss is not proof of completion. Accepted background leases require explicit
completion or Worker process recovery. On restart, exclusive journal ownership
allows that Worker to report prior-process leases as `OWNER_STOPPED`; it does not
resume their inference. The Server restores remote leases across its restart;
old Server-local leases belong to the departed process and are removed.

Transport closure proves the controlled local operation ended, not that upstream
billing/computation stopped. Permanent loss of a Worker journal cannot be solved
by assuming its calls finished. There is deliberately no automatic timeout-based
reclamation or force-release MCP control.

## Upgrade

Migration V83 creates the default group and moves **all existing connections** to
it. Old `httpSettings.maxConcurrentCalls` values are deliberately discarded, not
converted into per-connection groups or preserved as another limit. The shared
default limit is 4. Operators can explicitly split groups afterwards.

Migration V84 transfers those connection assignments to their model configurations
and removes the old connection field. It also adds TPM groups/state/receipts.

Worker Gateway protocol 23 is required for HTTP quota feedback and admission. Update Server
and all Workers together, including Workers without AI capabilities; update UI
clients before editing the new catalog. Protocol 21 CBOR byte-string encoding is
unchanged. Finish queued/in-flight operations before the coordinated cutover:
stored old AI request payloads can still contain removed connection/HTTP fields. They
are not rewritten or silently retried by this migration. Do not mix a new Server
with an old Worker bypassing admission, or an old UI with the new catalog. The
migration does not replay or erase Worker requests, change provider credentials,
or start any application.

Regression tests cover queue ordering/cancellation/catalog revisions, scoped
permit cleanup, SDK retry/cancellation boundaries, encrypted Worker recovery,
MCP controls and isolated PostgreSQL migration/ownership transitions. Local
fixtures are not acceptance tests of a live gateway account or proof of hidden
CLI request accounting.
