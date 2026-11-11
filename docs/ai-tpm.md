# HTTP token-quota admission

TPM is an opt-in policy on an **AI model configuration**, separate from its
concurrency group. `tpm: null` is the default. There are no automatically created
TPM groups, provider-name heuristics, quota preflight requests, or hardcoded quota
header names. One Server coordinates all its executors; it does not coordinate
another Server or external clients using the same upstream account.

This is a configured local admission policy, not a claim of exact upstream
accounting or a guarantee against every 429. Test the actual gateway installation
before enabling it for important work.

## Groups and configuration

`AiCatalog.tpmGroups` contains complete `AiTpmGroup` objects:

```json
{
  "id": "work-quota",
  "key": "account/model",
  "displayName": "Work model quota",
  "tokenLimit": 600000,
  "renewalPeriodSeconds": 60,
  "allowParallelRequests": false,
  "quotaUsageFraction": 1.0,
  "missingRetryDelaySeconds": 10
}
```

The immutable ID identifies local durable state. The human key is a separately
editable exact string and may contain `/`. Use different groups for independent
accounts even if a gateway returns equal quota keys. Model aliases sharing one
actual budget must be explicitly assigned to the same group. The configured
limit and period are not replaced by response headers.

The AI settings UI provides group CRUD and a policy editor on each model
configuration. Control MCP exposes `grz_ai_tpm_group_upsert` and
`grz_ai_tpm_group_delete`; both require Server-owner access and the current
`expectedRevision`. `grz_ai_model_configuration_upsert` sets the policy.
Deleting a fixed/fallback-referenced or actively leased group fails. Dynamic
selectors are not rewritten on deletion: their explicit fallback/error applies.

Example model configuration policy (header names are installation-specific):

```json
{
  "group": {"type": "fixed", "groupId": "work-quota"},
  "calculator": {
    "type": "builtin",
    "preset": "INPUT_PLUS_OUTPUT",
    "charactersPerUnit": 4.0,
    "multiplier": 1.0,
    "fixedOverhead": 0,
    "outputReserve": 8192
  },
  "feedback": {
    "type": "fields",
    "remaining": {"type": "header", "name": "custom-remaining-tokens"},
    "retryDelay": {"type": "header", "name": "Retry-After"},
    "retryFormat": "RETRY_AFTER"
  },
  "fallbackGroupId": null
}
```

The example coefficients/reserve are not recommended values for an unknown
installation. A configuration warning flags quota capacity below the model's
context window; context tokens and quota units are nevertheless distinct. If an
actual estimate cannot fit in the usable capacity, that call fails explicitly
instead of waiting forever at the head of FIFO.

## Selection, estimation and extraction

Selection happens from the **prepared HTTP JSON**, after provider mapping:

- `fixed`: one `groupId`.
- `request_value`: a JSON Pointer such as `/model`, `/reasoning/effort`, or
  `/reasoning_effort`. Its value must be a string equal to a configured group key.
- `command`: a Server command whose stdout is that key (one terminal newline is
  accepted). Unknown keys, invalid types, command errors or timeouts fail unless
  `fallbackGroupId` was explicitly set. This fallback applies only to selection.

JSON Pointer is the [RFC 6901 string form](https://www.rfc-editor.org/rfc/rfc6901).
An empty pointer selects the root; `~0` and `~1` escape `~` and `/`. Array indices
are strict. URI fragments, JSONPath and wildcards are not supported. A reusable
`shared.json.JsonPointer` resolves kotlinx-serialization `JsonElement` values.

Built-in calculators expose their formula and source link in the UI. They are
simple configurable **estimates**, not tokenizers:

- `CHARACTERS`: serialized JSON Unicode code-point count divided by
  `charactersPerUnit`, multiplied, rounded up, plus `fixedOverhead`.
- `INPUT_ONLY`: the same estimate over string values under known input/tool
  fields, without an assumed cache discount. The source explains
  [Anthropic input/output quotas](https://platform.claude.com/docs/en/api/rate-limits),
  not universal input-only accounting.
- `INPUT_PLUS_OUTPUT`: estimated input plus an explicit output reserve, or the
  actual request's `max_output_tokens`, `max_completion_tokens`, or `max_tokens`.
  Absence of both is an error. The shape is motivated by
  [TrueFoundry V2's combined usage](https://www.truefoundry.com/docs/ai-gateway/ratelimiting-v2);
  our advance reservation is not its measured debit.
- `OPENAI_MAXIMUM`: maximum of estimated input and the same output reserve,
  following the [documented OpenAI rate estimate](https://developers.openai.com/api/docs/guides/rate-limits).

All estimates must be positive integers. Text estimates do not calculate image,
audio, reasoning, cache, or installation-specific debit exactly. Use a trusted
command for such rules. The estimator never learns coefficients from `usage`.

Feedback extraction accepts a case-insensitive header name, an optional
`jsonPointer` inside a JSON-valued header, a `body` JSON Pointer, or a command.
Negative remaining values are retained as debt. Duplicate header values,
non-numeric remaining values, non-finite numbers and malformed pointers are
errors. The optional retry value supports `SECONDS`, `MILLISECONDS`, `HTTP_DATE`,
or `RETRY_AFTER` (seconds or HTTP-date). HTTP-date uses the response `Date` when
available, otherwise the executor's wall clock. A reset-to-full estimate must
**not** be configured as a required delay before every request.

Required feedback missing/invalid on a 2xx response fails that call through the
normal error path, after releasing its active slot. It does not pause a group,
reset its balance, or repeat inference. A 429 may omit feedback; the last state
and local estimate remain, with the configured missing-feedback cooldown.
Existing adapter retry rules still apply: SDK adapters retry only a completely
received 429 within their attempt/deadline budget. Timeout, cancellation,
incomplete streams and unknown outcomes are not permission to replay inference.

## Command contract

A command is `{executable, arguments: [...], timeoutSeconds}`. No shell expansion
or request interpolation occurs. If a shell is explicitly selected, its script
is trusted owner configuration. Commands run as the **Server OS user**, even for
Worker-executed inference. They are not sandboxed and must not contain secrets in
arguments or source. Configure programs/credentials on Server explicitly.

Selector and estimator stdin is the prepared request JSON plus a newline.
Estimator stdout is one integer. Feedback stdin is
`{status, headers: {name: [values]}, body, bodyUnavailable}`; stdout is
`{"remaining": -20, "retryAfterMillis": 1000}` (fields may be null on errors).
Commands must not log prompts, responses or credentials. stdout/stderr are each
bounded at 64 KiB; stderr is not included in user errors. At most four commands
run concurrently; their 1–120 second timeout includes waiting for that capacity.
Timeout/cancellation kills the command process tree. Script IO never runs in the
admission actor.

## Accounting and scheduling

A virgin group starts with zero consumed quota (full configured capacity).
Thereafter two balances must both admit the estimated debit:

1. A local safety balance with capacity `tokenLimit * quotaUsageFraction` and
   refill rate `tokenLimit / renewalPeriodSeconds * quotaUsageFraction`.
2. An observed-provider balance with the full configured capacity and refill
   rate, corrected using feedback.

Fresh feedback cannot replenish the independent safety limiter. Reducing the
fraction therefore slows both burst capacity and sustained local usage. Debt is
not clamped to zero. All normal elapsed-time calculation uses a monotonic clock.

Serial mode waits for full completion of the previous group call, not just
headers. An isolated response replaces the observed balance. In parallel mode,
response arrival order is not assumed to be debit/snapshot order: feedback is
reduced by estimates of other calls active at its admission plus calls admitted
later, including overlapping calls already completed. It can lower but not raise
the existing observed estimate. This intentionally can double-cover uncertain
spend and reduce throughput; it avoids optimistically releasing ambiguous
reservations. After the group becomes isolated, fresh feedback can reanchor it.

The actor preserves FIFO within a TPM group. A large head request cannot be
starved by smaller later requests. Independent groups still proceed. It handles
state transitions only; HTTP, commands and database writes occur outside it.
While TPM requests wait, one wakeup is scheduled for 10 seconds later. Wakeups
recompute elapsed time; they never carry incremental quota credits. A generation
number makes stale cancelled wakes harmless. Delayed scheduling can delay work,
but does not manufacture refill or accumulate a burst of credits.

A 10-second recheck is **not** an emulation of TrueFoundry V2's rolling window or
its asynchronously reported counters. Our linear refill follows the configured
policy used for the observed work installation; forwarded upstream headers and
TrueFoundry's own limits need not describe the same counter. No snapshot is a
provider-side reservation.

## Transport, persistence and upgrade

Supported JSON HTTP boundaries: OpenAI Chat Completions/Responses, Anthropic
Messages, compatible JSON operations and subscription HTTP. Configuring TPM on
a subscription model chooses HTTP up front, not a WebSocket failure fallback.
CLI adapters, realtime WebSocket and multipart STT have no TPM implementation;
configured unsupported operations fail before inference. Auxiliary/model-less
operations continue using the explicit default concurrency group, without an
arbitrary model policy.

SDK JSON preparation is bounded at 16 MiB. Feedback bodies are bounded at 2 MiB;
non-JSON, unavailable or larger bodies cannot satisfy a body-based policy, while
header-based policies still work. Subscription SSE feedback uses the terminal
response object's JSON. Header arrival never releases the inference slot.

Server persists debit and lease before allowing execution, then updated balances
and a completion receipt atomically. Receipts retain the terminal feedback error
for 30 days so duplicate `Finish` cannot debit twice or turn failure into success.
Late feedback beyond retention fails explicitly rather than being reapplied.
Workers encrypt and durably journal terminal feedback before sending it. A lost
acknowledgement retries only completion, never inference. Unconfirmed TPM
completion fails the current call and retains recovery data. Receipt retirement
is bounded opportunistic cleanup, not a scheduled service.

Restart retains saved balances/debt and remote reservations. It deliberately adds
no speculative refill for time spent offline; normal refill resumes after
recovery. Local prior-process slots are removed, but their estimated spend is not
refunded. This may wait longer after a restart; it cannot invent a fresh budget.

Migration V84 moves each existing connection's concurrency assignment to its
model configurations, removes the old connection field, and adds quota storage.
Worker Gateway protocol **23** is required. Drain existing operations and update
Server, Workers and UI clients together before editing the catalog. No live
account configuration is changed automatically by enabling this feature in a
release. See [AI admission](ai-admission.md) for ownership and cancellation.

## Acceptance checklist

Local tests cover parsing, estimator failures/timeouts, FIFO, parallel snapshot
ordering, debt/refill, cancellation, real localhost SDK HTTP, encrypted journal
recovery, MCP authorization/revisions and isolated PostgreSQL migration/state.
These are not work-account acceptance tests.

For the work installation, explicitly configure groups and estimates, start with
serial mode, confirm successful and 429 feedback, then compare burst and sustained
usage. Exercise long threads and parallel completion in reversed order before
allowing parallel mode. Reduce usable quota when an estimate is optimistic.
RPM, independent upstream limits, foreign traffic and `usage` reconciliation are
outside this first TPM implementation.
