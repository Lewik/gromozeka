# Proactive compaction and full-history retention

Compaction policy belongs to `AiModelConfiguration.compaction`:

```json
{
  "thresholdTokens": 140000,
  "proactiveThresholdTokens": 100000,
  "summaryModelConfigurationId": null,
  "tokenCounting": { "mode": "CURRENT" }
}
```

Both thresholds are fixed absolute token counts. The optional proactive threshold
must be positive and lower than the blocking threshold. Its default is `null`
(disabled); existing installations are not opted in automatically. Settings and
`grz_ai_model_configuration_upsert` expose the same policy. Resetting
`compaction` to `{}` disables both triggers.

## Full versus selected compaction

A full Harness-initiated compaction keeps roughly 80,000 characters of recent
text without summarizing that suffix. This applies to automatic blocking
compaction, proactive preparation, and manual selection of the entire effective
window. A partial explicit selection does not acquire an extra retained suffix;
its tool call/result closure is still included and unselected messages remain.
Selecting part of the history is not changed into a full operation merely because
adding paired tool messages happens to cover all messages.

Selection walks backward through complete messages. The message crossing 80,000
characters stays whole, and the boundary expands to include complete tool
exchanges and intervening messages. It is a soft character budget, not a tokenizer
or hard token cap. Textual content and instructions are counted, not JSON
wrappers or base64 media. A prior prefix checkpoint is summarized again rather
than treated as raw recent dialogue. Selective summaries do not arbitrarily
reset that boundary. Checkpoints are not charged as raw dialogue; any checkpoint
inside a retained whole-message suffix stays intact. Archived sources remain
available for history recovery.

If all effective raw history belongs to the suffix, there is no older prefix to
compact. Background preparation is skipped. A full manual/blocking operation
fails explicitly before inference instead of cutting a message, silently dropping
the suffix or entering an endless compaction loop. The user can select older
messages explicitly or adjust the blocking threshold. CONCATENATE still requires
at least two source messages and never calls a model.

## Preparation and application

After a completed, user-visible answer, the Runtime may start one preparation for
the conversation. Intermediate tool steps, failed/refused answers, and unpublished
response-review drafts do not start it. The trigger reuses ordinary-response
measurements when applicable and otherwise uses the local estimate; it does not
add a count API call after each answer. Foreground blocking checks retain the
configured counting policy.

Preparation captures an immutable prefix and its retained suffix. It runs under
ordinary model admission and quota policies, with no special priority or reserved
capacity. It can consume the last available model slot. The intended benefit is
using the time while the human reads the answer, not a guarantee of zero waiting.

A result is not applied by the background coroutine. It is stored as READY and
consumed at the next safe model-request boundary by the normal Conversation
Runtime owner. New messages appended after capture are preserved. Input edits,
thread changes, Agent/configuration/pin changes and access revocation prevent
stale application. Credentials are not stored in preparation receipts; scope
fingerprints are hashes, and public Runtime snapshots expose status, not prepared
content.

At the blocking threshold, a running preparation is awaited rather than duplicated.
A confirmed preparation failure allows the ordinary blocking compact of the
current history. A result that still leaves the next request over the blocking
threshold causes an explicit error, not an unbounded loop or an oversized normal
request. A finished preparation never initiates an unsolicited model answer.

## Cancellation, persistence and recovery

Preparation receipts live in the existing PostgreSQL Runtime scheduling state.
They retain the operation ID, owner session, source task/turn, scope and history
fingerprints, counts, lifecycle state and (while READY) checkpoint. Only one
receipt is current per conversation.

Stop and Interrupt (including double Escape) atomically cancel RUNNING/READY
preparations even when the foreground is idle. The owned inference coroutine is
cancelled too. Late completion cannot replace CANCELLED with READY, and cancellation
does not trigger an ordinary fallback. Stopping does not undo a history commit
that already won the transaction race.

Applying a prepared result locks Runtime before Conversation. The READY check,
COW history change and APPLIED receipt are one SQL transaction. Stop winning first
prevents application; application winning first cannot be undone by a later Stop.
Storage failure rolls back both history and the receipt. Appends committed before
COW are copied into the new thread; complete tool pairs and original history
remain intact.

READY results survive a Server restart. A RUNNING receipt owned by the previous
Server session becomes a visible failure with an unknown outcome; inference is
not replayed. Unknown provider outcomes likewise do not trigger automatic fallback,
even after a configuration change. Inspect the incident, or explicitly use Stop to
discard that preparation before making a new request. Discarding the receipt does
not assert that the old remote provider performed no work.

## Native adapters

The 80,000-character suffix requires compaction of an older prefix, not mutation
of the entire live provider session. Adapters advertise detached native support
separately from ordinary native compaction support.

- OpenAI Responses uses the standalone compact endpoint. Its returned native
  replay window is retained intact, followed by the saved suffix and new appends.
- OpenAI Subscription uses compact-only Responses Lite when available, with a
  preparation-specific transport namespace so it cannot mutate the foreground
  incremental-replay cache. Other profiles use the configured text summarizer.
- Claude Code's live-session `/compact` is not a detached prefix operation. These
  Harness full/prefix compactions therefore use the configured text summarizer.
  With a Harness threshold enabled, competing intrinsic Claude auto-compaction is
  disabled. Summary calls also disable it. With the Harness policy disabled,
  ordinary intrinsic provider compaction retains its provider-defined behavior.
- Other routes use the existing text summarizer unless their adapter explicitly
  supports safe detached native compaction. A failed native operation is not
  silently retried through a different provider.

Provider-emitted checkpoints are not arbitrarily rewritten to manufacture a
retained tail. Native checkpoints must identify an actual prefix of the captured
provider input, and signed/opaque state keeps its established replay contract.

Server and Workers must be updated together to Gateway protocol **30**. This work
adds no deployment or automatic update operation.

## Diagnostics and verification

Lifecycle logs use `COMPACTION` with operation IDs, mode, source/retained counts,
input estimates, summary character counts, durations and bounded failure metadata.
Waiting, cancellation, stale-result discard and blocking fallback are distinct.
History bodies, prompts, raw provider errors and credentials are not logged by
these lifecycle events. The public Runtime status distinguishes preparation,
readiness and failure, and keeps Stop available for idle background work and
unknown outcomes.

Regressions cover full/partial selection, whole-message/tool boundaries, appended
suffixes, readiness invalidation, configured thresholds, normal and reviewed
answer boundaries, one-operation waiting, confirmed failure fallback, public Stop,
late completion, restart, transport isolation, permissions and PostgreSQL COW/
cancellation atomicity. The database race test observes an actual blocked lock,
not merely repeated unsynchronized concurrency. Mock/fixture verification must
not be reported as live native-provider acceptance.
