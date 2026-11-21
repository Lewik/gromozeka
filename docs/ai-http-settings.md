# AI SDK HTTP connections

The existing OpenAI-compatible Chat Completions, OpenAI Responses and Anthropic
Messages connections accept `httpSettings`. This configures transport and
credentials, not the model provider or inference protocol. The connection still
selects its existing API. Model IDs are sent unchanged.

Example for Anthropic Messages through a gateway:

```json
{
  "connectionKind": "anthropic_api",
  "id": "gateway-messages",
  "displayName": "Gateway Messages",
  "enabled": true,
  "concurrencyGroupId": "default",
  "baseUrl": "https://gateway.example.com/api/llm",
  "apiKey": { "secretType": "file", "path": "/etc/gromozeka/gateway-token" },
  "httpSettings": {
    "authentication": "BEARER",
    "caBundlePath": "/etc/gromozeka/company-ca.pem",
    "proxyUrl": "http://proxy.example.com:8080",
    "noProxy": ["localhost", ".internal.example"],
    "maxAttempts": 3,
    "extraHeaders": {
      "User-Agent": "Gromozeka",
      "X-TFY-METADATA": "{\"application\":\"gromozeka\"}"
    }
  },
  "executionTarget": { "executionTargetKind": "worker", "workerId": "work-ubuntu" }
}
```

The URL is the complete inference prefix. SDKs append `chat/completions`,
`responses`, or `v1/messages`, respectively. Do not append these operation paths
or duplicate `/v1` in the configured prefix. Credentials, query strings and
fragments are not accepted in the base URL. Use HTTPS for non-local gateways.
There is no error-driven URL, protocol or model fallback.

## Chat Completions through TrueFoundry

When the gateway only exposes `POST /chat/completions`, use an
`OPENAI_COMPATIBLE` connection (`connectionKind: "openai_compatible"`), not
`OPENAI_API`. The existing connection kind selects the adapter; changing only
the base URL does not change the protocol. Keep the exact gateway inference
prefix, credential source, executor, CA/proxy settings and required headers.
Do not append `/chat/completions` to the base URL: the SDK adds that path.

Register the exact gateway model ID with a `CUSTOM` model spec and bind its
configuration to the Chat connection. Declare only the capabilities and limits
known to work on that route. For OpenAI-style reasoning, use the existing
`REASONING_EFFORT` encoding and leave reasoning `display` unset: Chat has no
Responses summary-control parameter. Choose `MAX_COMPLETION_TOKENS`, `MAX_TOKENS`
or `OMIT` explicitly for the route. Claude thinking through Chat retains its
separate `ANTHROPIC_THINKING` encoding; neither is guessed from a model name.

Chat replies can contain `reasoning_content` for display and `thinking_blocks`
for continuation. In addition to `thinking`/`redacted_thinking` blocks, the
adapter accepts OpenAI encrypted blocks with `type: "reasoning"`, a nonblank
`id` and nonblank `encrypted_content`. The raw assistant message preserves
these blocks, their order and additional nested fields through persistence and
subsequent tool turns. Ciphertext is never rendered as reasoning text. An
encrypted-only message is retained even without a readable summary. Missing or
malformed replay fields fail explicitly instead of silently losing state.
Native replay remains scoped to the same connection, model, provider and thread.

There is no automatic fallback to `/responses`, and Gromozeka does not inject
`x-tfy-openai-use-responses-api`. However, the gateway itself can serve OpenAI
Chat reasoning through an upstream Responses call. Its routing and virtual-model
backend affinity are separate from the client protocol; verify them on the target
installation rather than assuming an external Chat endpoint removes every
upstream Responses dependency.

Responses encrypted compaction checkpoints are not Chat summaries. For a route
change, prefer an explicit readable handoff and a fresh conversation instead of
transplanting opaque history. Chat does not advertise Responses auto-compaction;
starting fresh also does not add automatic compaction for future long conversations.
This change does not add a separate summarization service or change stored history.

`TrueFoundryChatCompletionsTest` exercises the production runtime and real SDK
against a local Chat-only fixture (other paths return 404), including a complete
429 rejection, encrypted reasoning/tool replay, JSON Schema, usage and safe
malformed-response failures. `OpenAiThinkingReplayTest` covers JSON persistence,
reasoning-only replies and replay scope boundaries. These are synthetic gateway
contracts, not acceptance tests against an AMAT installation.

Reference: [TrueFoundry Chat reasoning and multi-turn replay](https://www.truefoundry.com/docs/ai-gateway/chat-completions-advanced#reasoning-on-openai-azure-openai-and-microsoft-foundry).

## Blocking conversation compaction

Compaction is configured on `AiModelConfiguration`, not `AiModelSpec`:

```json
{
  "compaction": {
    "thresholdTokens": 140000,
    "proactiveThresholdTokens": 100000,
    "compactionModelConfigurationId": null,
    "tokenCounting": { "mode": "CURRENT", "modelConfigurationId": null }
  }
}
```

The threshold is an absolute input-token count below the model's context window.
Percentage input in Settings is converted to tokens; percentages are not stored.
`thresholdTokens: null` disables the Harness automatic trigger (the default for
new and migrated configurations). A provider's intrinsic session compaction can
still occur; its emitted checkpoints also use the COW commit. The optional compaction-model configuration is used only for
text compaction; null means the active agent's model. Existing Control MCP
`grz_ai_catalog_get` and `grz_ai_model_configuration_upsert` read and replace these
settings. `compaction: {}` resets the whole policy, including token counting. The global `MESSAGE_SQUASH` runtime
assignment and model-spec threshold policy have been removed.

Before an ordinary model call, the Conversation Runtime checks the complete next
request, including system prompts, tools, response schema and projected history.
At the threshold it blocks generation, executes one compaction, commits a new
history version (COW), and rebuilds the request. If the result still exceeds the
threshold, it stops with an explicit error instead of repeatedly compacting or
sending the oversized request. An optional earlier `proactiveThresholdTokens` starts
one preparation after a completed answer. At the blocking threshold an existing
preparation is awaited, not duplicated. See [proactive compaction](proactive-compaction.md)
for cancellation, recovery, retained suffixes and native-adapter restrictions.

### Context counting policy

Normal continuation uses the last compatible ordinary response's context usage plus
local size/4 estimates for the changed overhead and appended messages. Server performs
this check without a provider request or a separate full-payload Worker inspection RPC.
Approaching the threshold does not itself request a provider count. The estimate is
not a tokenizer guarantee; the configured threshold must leave room in the model window.
A baseline belongs to its original model, connection, request semantics, thread and
content-bound history prefix, including the covered output. Edits with unchanged IDs,
shortening, forks and compaction cannot relabel an old measurement as a new one.
System/tool overhead changes are reflected in the local estimate. Cumulative billing,
helper usage and compaction-pass usage never substitute for ordinary context usage.

`compaction.tokenCounting` selects what to do when no compatible baseline exists:

- `CURRENT` (default): use the current route's count API when the adapter implements
  one; otherwise estimate locally. Currently implemented count APIs are OpenAI Responses
  and direct Anthropic Messages, not OpenAI-compatible Chat routes.
- `LOCAL`: never call a token-count endpoint, even if the adapter implements one.
  Ordinary measured usage is still reused; missing measurements use local estimates.
- `MODEL_CONFIGURATION`: require `modelConfigurationId` identifying an enabled text
  model configuration with a count API. That configuration selects model, connection,
  credentials, execution target and quota policy. Its own counting policy is not followed
  recursively. This is an explicit data transfer to another provider/connection and the
  count is marked approximate for the main model. Signed thinking and opaque checkpoints
  are not forwarded across models: their local estimate is added to the portable-window
  count. Archived pre-compaction sources are not expanded just to count them.

Select an alternate counter, for example:

```json
"tokenCounting": {
  "mode": "MODEL_CONFIGURATION",
  "modelConfigurationId": "corporate-token-counter"
}
```

A configured counter failure remains explicit; it does not silently switch to local
estimation or another endpoint. The next ordinary main-model response supplies the next
baseline. Legacy measurement metadata is ignored once, without rewriting history.
Dynamic native-compaction availability is inspected only when compaction is necessary,
with provider counting explicitly disabled. The main model, not the alternate counter,
selects the compaction executor. Gateway protocol **28** carries that flag explicitly;
Server and Workers require a coordinated upgrade.

The executor is selected before execution:

- OpenAI API Responses: `/responses/input_tokens` counts fresh/invalidated contexts when selected, and
  `/responses/compact` returns a complete native replay window. Its order and
  encrypted items are preserved. Compact-only requests omit `instructions` to
  avoid invalidating subsequent preserved reasoning; ordinary generation still
  receives its instructions. Ordinary `/responses` calls remain stateless
  (`store=false`) and no longer send `context_management` thresholds.
- OpenAI Subscription: Responses Lite uses an explicit compact-only trigger.
  Other profiles use text compaction. The adapter's decoded-opaque estimate
  is calibrated to scoped usage; it is not a provider token count. Native replay
  may retain raw items according to its protocol, independently of text policy.
- Claude Code: live-session `/compact` is not a detached prefix operation. Harness
  full compactions retain a suffix and therefore select the configured text
  compaction model before any native session mutation. With a Harness threshold
  enabled, competing intrinsic auto-compaction is disabled; text-compaction calls
  disable it too.
  When the Harness trigger is disabled, provider-emitted checkpoints retain their
  native COW/replay contract rather than being rewritten to manufacture a tail.
- Anthropic Messages: `/messages/count_tokens` counts fresh/invalidated contexts when selected;
  text compaction uses the configured model. Bedrock has no universal count endpoint
  in this adapter and uses an explicitly marked estimate.
- Chat Completions, including TrueFoundry: no speculative count endpoint or header
  is used. The estimate uses the same mapped messages, tools and response schema
  as the actual SDK request, after model/connection/branch replay filtering.
  Ciphertext, thinking signatures and redacted blobs are not tokenized as text.
  Compatible opaque reasoning is still sent unchanged; its measured contribution
  comes from usage calibration, not encrypted byte length. Input and output use
  the same estimator, calibrated with the last compatible request's measured
  input/output usage plus request changes. Cumulative billing is not occupied
  context. Changing thread/model, source prefix or estimator identity discards
  the old calibration. Older untagged baselines are ignored without changing the
  archive. Without compatible measurements this remains a text-size estimate,
  not an exact tokenizer, hidden-reasoning or multimodal token count.

Context-size accounting is separate from TPM quota reservation: calibration does
not change the configured quota formula or balances. Count, native compact and
text-compaction HTTP operations retain their model configuration's admission policy. Required TPM
feedback must be compatible with those endpoint responses. Quota calculators that
need an output reserve must configure it explicitly when the request omits an
output limit; the Harness does not invent a compaction-output cap or bypass quota policy.

A failed blocking counter or native operation is an error, not permission to retry
through another route. Confirmed failures of background preparation follow the
explicit ordinary-compaction fallback; cancellation and unknown outcomes do not. Every committed compaction creates a new Thread; canonical source
messages and original IDs remain archived. Runtime roots, queues and stable
Conversation/Agent collaboration addresses continue. New provider output after a
native checkpoint receives the new replay scope; archived opaque state is not
retagged. Server and Workers must use the matching Gateway protocol (currently version 30).

Text auto-compaction always uses the continuation-oriented COMPACT prompt.
Full compactions retain approximately 80,000 recent text characters as whole
messages and complete tool groups; the boundary-crossing message remains whole.
Partial explicit selections do not gain an extra suffix. The older prefix is
compacted semantically, preserving current instructions, verified results,
obligations, constraints and next actions. There is no separate compaction-length setting. Readable material and supported
committed image/PDF attachments are sent to the helper, not raw provider replay.
One-shot revealed secrets are reserved for the ordinary request and are not sent
to the counter or compaction model. DISTILL remains a manual-only prompt variant;
CONCATENATE remains non-generative. Manual AI compaction uses the single automatic
responder, or the sole connected agent; ambiguous multi-agent selection is an
explicit error rather than an arbitrary model choice.

Text-compaction calls do not inherit the ordinary model configuration's output cap.
Optional output-token fields are omitted. APIs requiring an output budget use
model-declared limits and the available request budget; missing required model
knowledge fails explicitly. Truncated, refused or incomplete compactions are never
committed. Native compaction billing is not mislabeled as retained context usage.

Regression coverage uses the production SDKs against local HTTP fixtures,
serialization/replay, Runtime tests and isolated PostgreSQL schemas. These are
not acceptance tests against a live TrueFoundry deployment. Cross-provider
opaque replay remains scope-checked; use an explicit readable handoff when
changing to an incompatible route.

Contracts: [OpenAI compaction](https://developers.openai.com/api/docs/guides/compaction),
[OpenAI preserved reasoning](https://developers.openai.com/api/docs/guides/preserved-reasoning),
[TrueFoundry Chat usage and reasoning](https://www.truefoundry.com/docs/ai-gateway/chat-completions-advanced#reasoning-on-openai-azure-openai-and-microsoft-foundry).

## Synthetic tool-call IDs

Memory, command-completion and monitor-notification pairs use deterministic nine-character
`[A-Za-z0-9]` IDs. SHA-256 of the full stable notification key is reduced to nine
base62 digits; monitor delivery keys include the event batch and terminal marker.
The same ID is used for the call and its result, including redelivery.

Message IDs, command/monitor IDs, lookup arguments and provider-generated tool-call
IDs are unchanged. Existing stored notifications retain their original IDs. The
shared model-history projection normalizes only those legacy synthetic pairs for
Responses, Chat Completions and Messages; it does not rewrite the database. A
collision in the outgoing context is an explicit error, never a merged pair.
Native CLI raw-history/session handling is unchanged.

If a notification was interrupted after its call was stored before the upgrade,
replay reuses that stored ID for the missing result. Both become the same short ID
in the API view. Local regressions capture actual SDK HTTP payloads for all three
protocols; support for a particular gateway/model still depends on its contract.

## Authentication and rotation

`apiKey` remains the single credential slot, also when it contains a gateway
Bearer token. Select exactly one existing secret-reference type:

- `{"secretType":"inline","value":"..."}`: stored connection secret. Never put
  a real value in source files or logs. Public client catalogs redact it.
- `{"secretType":"environment_variable","name":"GATEWAY_TOKEN"}`: the selected
  execution process's environment, not the UI client's environment.
- `{"secretType":"file","path":"/absolute/executor/path"}`: executor-local
  plaintext UTF-8, at most 64 KiB. Leading/trailing whitespace is removed. The
  resulting HTTP token must be nonempty ASCII without whitespace. No shell,
  tilde expansion, command evaluation or environment substitution is performed.

A missing, unreadable, empty, malformed or oversized selected file is an error.
An unresolved selected environment variable is an error. Neither case falls back
to another source, vendor key or public endpoint.

`PROVIDER_DEFAULT` selects the existing API's authentication header: Bearer for
OpenAI APIs and `x-api-key` for Anthropic. `BEARER` explicitly selects
`Authorization: Bearer ...`, including for Anthropic, without also sending
`x-api-key`. It does not enable login, refresh, workload federation or subscription
authorization.

For existing native OpenAI/Anthropic connections only, an absent credential
reference **and** absent base URL **and** `PROVIDER_DEFAULT` retain the native
`OPENAI_API_KEY` / `ANTHROPIC_API_KEY` default source and native public endpoint.
A configured endpoint always requires an explicit credential reference, even if
its URL happens to equal a vendor's public URL. No provider profile-file or
alternate-auth-token discovery is performed.

Each logical call owns an SDK session, but network resources are shared by the
executor-local `SdkHttpClientPool`. The selected credential file is reread before
that call, even when the runtime object is reused; that token stays fixed across
the call's controlled HTTP attempts. Credentials and extra headers are not cached
in the shared network client. Catalog secret edits are picked up when the next
runtime context resolves the updated catalog; no application restart is needed.
Updating an unrelated shell's environment cannot change an already running JVM's
inherited environment. Use file or catalog rotation for that case.

JWT-shaped tokens with a numeric `exp` can produce a log warning when their
unverified expiry is past or within five minutes. This is not signature checking,
authentication validation, or grounds to reject a call. Other tokens are passed
unchanged. No token or decoded private claims are printed. A 401 means rejected
authentication, not necessarily expiry; a 403 is reported separately as access
denial. Neither triggers an automatic login or token refresh.

## Connection reuse and measurements

OpenAI Chat/Responses, direct Anthropic Messages, and their existing embeddings,
speech, token-count, compaction and model-discovery calls use the same transport
owner. Different conversations and runtime instances can reuse connections for
the same configured connection ID, inference prefix, effective proxy and CA
content. Different connection IDs have separate pools. Resources are local to
the Server or Worker process executing the call; they are not shared between
processes or machines.

Each call keeps its own cancellation, deadline, feedback and response-body
ownership. Closing one session does not shut down the shared dispatcher or evict
another session's connections. Harness admission remains the concurrency limiter;
sharing the dispatcher adds no separate per-host request limit. Automatic retries
and redirects remain disabled. Proxy/endpoint/CA changes retire the old resource;
its active calls can finish before it is closed. Process shutdown also closes
active retired sessions. Idle sockets keep OkHttp's defaults (five idle sockets,
five minutes); unused resource entries are retired on later acquisitions after
five idle minutes. No connection-count or keep-alive UI setting is introduced.

`SDK HTTP` logs contain only outcome, elapsed/connect/TLS milliseconds, connection
attempt count, whether an existing connection was reused, and request/response
body byte counts. These are native HTTP measurements, not complete Harness
preparation or admission times. Connect time includes TLS rather than being an
additional disjoint phase. Logs contain no URL, headers, prompt/response content,
tokens or exception text. They do not enable SDK traffic dumps.

`SdkHttpPoolingTest` uses real local HTTP/HTTPS with synthetic TrueFoundry-style
prefixes, Bearer authentication and metadata. Its synthetic HTTPS benchmark
compares fresh transport owners (the previous per-call lifecycle) against one
shared owner while sending the same long history. It reports timings and asserts
socket reuse, not a hardware-dependent speed threshold. This is not acceptance
against a real gateway and does not establish a production latency improvement.

Subscription WebSocket and Bedrock transports are separate and unchanged. Connection
pooling itself does not enable SSE, `store`, `previous_response_id`, or provider-side
conversation state; those are independent opt-in settings described below.

## Responses streaming and stored continuation

`OPENAI_API` connections have two independent opt-in options, both false by default:

```json
{
  "responsesStreamingEnabled": true,
  "responsesStoredContinuationEnabled": true
}
```

These are Responses protocol settings, not `httpSettings`. They work with the
ordinary native API and compatible Responses gateways such as TrueFoundry.
They do not change Chat Completions, Anthropic, Bedrock or subscription WebSocket.
No production connection is enabled automatically.

With streaming enabled, the installed SDK consumes SSE internally and returns the
same complete runtime response as non-streaming execution. A terminal
`response.completed`, `response.incomplete` or `response.failed` event is required;
EOF or partial tool-argument events alone never become a successful response.
The stream is closed at its terminal event, without waiting for EOF or unrelated
later events. Existing final-response parsing and validation still gate tools.
Cancellation/deadline closes the native body even after headers. This does not add
character-by-character GUI output or automatic replay of interrupted requests.

Stored continuation uses `store:true` only for ordinary `CONVERSATION` calls with
complete actor/project/conversation/thread/agent scope. A bounded executor-local
cache keeps at most 128 opaque response IDs, prefix hashes and item counts. It is
shared across runtime instances but separate from the HTTP connection pool.
Endpoint, selected credential fingerprint, connection HTTP settings, execution
target, model configuration and model also isolate the cursor. No prompt/output
text or raw credential is retained in that cache; no database migration is needed.
Restart/eviction returns safely to one full request.

The next request uses `previous_response_id` only when its actual lowered input
contains the complete expected prefix, including the previous native output. It
then sends only the suffix, including function-call results. Message IDs alone
never prove continuation. Edits, shortened context, incompatible projection,
fork/compaction or changed scope produce a full request. Top-level instructions,
tools, response format and current permissions are rebuilt on every call; changes
inside `Message.instructions` participate in the actual input comparison.
One-use revealed-secret context forces a full `store:false` request and forgets
the cursor, so it cannot become an implicit stored parent. Context queries,
isolated/helper calls, token counting and detached compaction remain stateless.
Failed/incomplete/refused results, parsing failures, cancellation and quota errors
do not advance the cursor. Concurrent stale completions cannot replace a newer one.

The response ID is opaque: return the ID from the gateway verbatim, including any
TrueFoundry virtual-model wrapper. Do not extract a provider-native ID or infer a
backend. An explicit SDK `previous_response_not_found` rejection for
`previous_response_id` at HTTP 400/404 can trigger one full recovery attempt only
when the whole rejection was received. It shares the original logical call's
`maxAttempts`, deadline, credential snapshot and admission policy with 429 retries.
`maxAttempts:1` disables this recovery too. Other 400/409, auth, 5xx, malformed,
truncated and ambiguous failures never trigger a hidden full replay or route change.

At DEBUG, `RESPONSES_WIRE` reports reason, stored mode, item counts and serialized
full/sent JSON body bytes (including the SSE flag). Its extra body serialization
runs only when DEBUG is enabled; normal INFO logging does not compute these sizes.
These are exact serialized payload sizes, not proof that a failed connection
transmitted every byte. At INFO, `RESPONSES_HTTP` reports mapping and logical
adapter milliseconds. Neither includes the whole Harness cycle
before the adapter. Logs contain no URL, raw ID, prompt/response, headers, credential
or exception body, including in the logging thread name. Upload reduction does not
reduce the model's effective context or bypass compaction/token limits.

TPM accounting retains the full effective input instead of reserving only the
small uploaded suffix. Group selectors see exact wire JSON; when an explicit
`accountingRequest` accompanies a continuation, both built-in and command quota
calculators receive the expanded full-context JSON. This is an intentional contract
extension for stored continuation; ordinary calls are unchanged. Server checks that
only input/parent fields differ and that the sent input is the accounting suffix.
For SSE, bounded final Response JSON supplies body feedback rather than raw SSE
framing; header feedback is unchanged. No quota policy is disabled. See
[HTTP TPM policies](ai-tpm.md#responses-continuation-accounting).

Worker Gateway protocol **34** carries these options and the accounting view.
Update Server and Workers together; older Workers must not silently ignore them.
Local SDK/SSE fixtures are not live acceptance of a particular gateway deployment.

## TLS, proxy and headers

- `caBundlePath` is an absolute executor-local path to additional X.509 trust
  certificates. These supplement JVM/system trust; they never replace it or
  disable TLS/hostname checks. Bad or missing bundles fail before HTTP. Current
  bundle bytes are read for each logical call. TLS resources are reused only for
  identical bytes, so rotation at the same path is observed before reuse.
- `proxyUrl` supports an HTTP proxy endpoint, including CONNECT for HTTPS
  inference. HTTPS-to-proxy, SOCKS and proxy credentials in the URL are not
  supported. An unspecified proxy leaves SDK/JVM system proxy selection intact;
  the setting does not import the UI's network configuration or interpret
  `HTTP_PROXY`/`HTTPS_PROXY` itself.
- `noProxy` applies to the configured inference endpoint. Entries are exact
  hostnames/IP literals, `.domain` suffixes (including the domain itself),
  optionally with ports, or `*` to force direct access. IPv6 literals can be
  bracketed. CIDR and embedded wildcards are rejected. Matching does not use DNS
  lookups or substring matching. Inference redirects and transport-level automatic
  retries are disabled; a redirect is an error, not a new route.
- `extraHeaders` is a JSON object of non-secret string values. Names must be
  unique ignoring case; line breaks and non-ASCII header values are rejected.
  Escape Unicode inside JSON-valued headers. `User-Agent` overrides the SDK
  default. Authentication, cookies and transport-framing headers are reserved.
  In particular, do not put `Authorization`, `x-api-key` or proxy credentials here.
- `X-TFY-METADATA` must contain a JSON object of strings, with at most 128 Unicode
  characters per value. Other gateway-specific static headers, such as
  `x-tfy-strict-openai: false`, can be configured without dedicated switches.

SDK request/response logging is explicitly disabled. SDK exceptions exposed by
the inference runtimes retain a safe HTTP status/message, not raw response bodies,
headers, causes or private gateway diagnostic IDs. This intentionally reduces
provider error detail in client-visible failures.

## Persistence and execution boundary

HTTP settings and secret references use the existing catalog persistence,
revision checks, secret mutations and redaction. File/env references are not
resolved while saving, serializing or displaying the catalog. They are resolved
on the chosen Server or exact Worker during execution. The UI displays only
source metadata such as the environment variable name or file path.

Worker Gateway protocol 22 adds Server-owned AI admission callbacks and durable
Worker leases. Protocol 20 added connection-scoped model listing on the exact Worker.
Protocol 19 introduced HTTP execution settings, logical deadlines and typed AI
failures; protocol 18 introduced HTTP settings and the file secret kind. Upgrade Server, Worker **and UI clients** together. Older clients cannot
preserve the new fields or decode the new secret kind; mixed-version editing is
not supported.

The HTTP connection-settings contract applies to the three finite inference APIs,
not subscription backends or Bedrock. Shared admission additionally covers
embeddings/audio and native adapters; it does not make their transports identical. Explicit model discovery is described below. Health endpoints and
target-installation acceptance remain separate. Memory orchestration has not
been changed; its legacy retry wrapper is outside this execution contract and
must not be assumed to honor typed terminal failures if it is enabled later.

## Execution budget, concurrency and cancellation

- Concurrency is controlled by [AI concurrency groups](ai-admission.md), not
  `httpSettings`. Each model configuration names one `concurrencyGroupId`, shared
  across Server and Workers. A group limit is 1–64 or explicit `null` for unlimited.
  Optional [TPM admission](ai-tpm.md) is configured separately on the model
  configuration and uses its explicitly selected request/feedback contract.
- Each HTTP attempt acquires admission separately. Client/credential setup for
  the first attempt and full response consumption are inside admission; retry
  backoff is outside it. The per-call client and credential snapshot remain the
  same across attempts. Cancellation or expiry while queued starts no HTTP and
  releases no other call's permit. A changed group limit affects waiting/new
  attempts without cancelling active calls; moving a model configuration to a
  different group does not retag already active leases.
- `defaultParameters.timeoutSeconds` on the model configuration now controls
  this logical call: 1–86400 seconds, default 600. Parameter preparation, queueing,
  credential/CA loading, backoff and HTTP consume the same deadline. A shorter
  caller deadline or Server AI gateway timeout wins. The deadline travels in the
  Worker AI request instead of restarting on delivery. Clock synchronization is
  required, as it already is for Worker delivery deadlines. Local elapsed-time
  accounting uses a monotonic clock.
- Blocking local file/CA setup is not forcibly interrupted; cancellation/expiry
  is checked before allowing any subsequent HTTP start. This is not a guarantee
  that an unresponsive filesystem operation returns promptly.
- The adapter uses the existing SDK serializers/protocol implementations over an
  owned OkHttp transport. Cancellation controls native calls through response
  parsing, including a socket stalled after headers; cancelling an outer SDK
  future alone is insufficient. Connections are closed with their per-call client.
- Server cancellation durably marks the same Worker request, without resubmitting
  the action. An offline Worker cannot receive cancellation immediately; the
  propagated deadline remains an independent local limit. Closing an HTTP socket
  does not prove that gateway/upstream inference or billing stopped.

## Conservative retries and typed failures

`maxAttempts` is 1–5, default 3, **including the first HTTP attempt**. Set it to 1
to disable application retries. SDK retries, connection-failure retries and
redirect following are disabled for these inference calls, so attempt limits do
not multiply across those layers. A gateway's internal provider retries/fallbacks
are outside this HTTP counter and need separate gateway policy.

Ordinary retries require a completely received HTTP 429 rejection. Opt-in stored Responses additionally permits one explicit missing-parent recovery, within the same attempt cap and deadline (see Responses streaming and stored continuation above). There is no retry
for 401/403, 408/409, 5xx, TLS/network errors, incomplete bodies, malformed model
responses or successful responses with an incomplete model outcome. These can
be partial/unknown outcomes; no route/model change or automatic token refresh is
attempted. Receiving headers alone is not enough to retry a truncated 429 body.

`Retry-After` accepts seconds or an HTTP date and is a lower bound. An additional
0–250 ms jitter is bounded; without a valid header, exponential backoff starts
at 250 ms and is capped at 4 seconds. A delay that will not fit the remaining
budget ends the call instead of retrying earlier than requested. Cancellation
during backoff never starts another attempt.

`AiCallFailure` carries a safe kind, outcome (`NOT_STARTED`, `REJECTED`, `UNKNOWN`),
HTTP status, observed attempt count and parsed delay where known. Unknown Worker
outcomes use an unknown count, not a fabricated zero. An available Worker request
id permits inspection of that same action. SDK bodies, headers, exception causes
and suppressed exceptions do not cross the boundary. `AiCallException` is a
**terminal logical-call failure**: callers must not wrap it in another automatic
retry loop or infer retryability by matching message text.

A semantic AI failure is a typed payload inside a successfully transported Worker
response. Request-journal replay returns the saved failure, not another model
call. Worker cancellation, loss of result retention, execution failure and Server
wait expiry remain explicit failures/unknown outcomes, never reasons to submit a
replacement request. Known SDK output parsing is included in this safe boundary;
internal request/model-contract validation still fails before HTTP.

Tests cover real localhost HTTP/TLS and stalled sockets, SDKs, the production
Worker executor with JSON/CBOR codecs and receipt replay, and the Server durable
request path with an in-memory repository. This does not claim a separately
running Worker/gateway socket, a live UI, or a real TrueFoundry installation.

## Explicit gateway model discovery

`grz_ai_connection_models` is a Server-owner, read-only Control tool. Its only
argument is `connectionId`, an exact saved connection ID. It refreshes the catalog,
captures that connection and revision, and performs an explicit `GET <baseUrl>/models`
on the connection's configured Server or exact Worker. There is no temporary model
configuration or invented model spec involved. The listing never creates or edits
catalog entries, changes assignments, or starts a model generation.

The operation deliberately targets the OpenAI-compatible models endpoint described
by TrueFoundry, irrespective of whether inference uses Chat Completions, Responses
or Messages. The connection must be enabled, use one of these three SDK HTTP types,
and have an explicit base URL. Disabled/unsupported connections and absent explicit
URLs fail before credential resolution or network access. No alternative endpoint
is probed after an error. Native provider-specific models endpoints such as
Anthropic `/v1/models`, health/version probes, pagination, automatic import and
background synchronization are not added by this feature. Use manual configuration
when that endpoint is unavailable.

The request uses the selected token source/authentication mode, extra headers,
CA trust and proxy policy from the connection. It uses the connection's Server-owned concurrency
group and has one 30-second overall deadline, including Worker delivery and queueing.
It sends one GET attempt: no application retries, including 429, and no redirects.
An additional native exchange guard prevents OkHttp's automatic bodyless-GET
follow-up for a 503 with `Retry-After: 0`; inference's one-shot POST-body protection
alone would not cover this case. Failed requests retain safe typed error details
under the tool's `error.aiFailure`, rather than appearing as successful empty lists.
Cancellation marks the same Worker request and closes active HTTP; it never resubmits
that action. Worker requests retain the authenticated caller for access revalidation.

The response must be an OpenAI-compatible `{ "object": "list", "data": [...] }`.
Candidate fields are `id` and optional `object`, `created`, `owned_by`; unknown
fields are not converted into capabilities, limits, reasoning settings or defaults.
IDs are preserved verbatim, including the provider-account prefix. Empty lists are
valid, not authentication errors. Malformed entries, duplicate IDs, pagination with
more pages, bodies above 4 MiB, or lists above 10,000 entries fail as a whole instead
of returning a silently incomplete list.

Results include the captured catalog revision, selected execution target and
measurement time. They are observations of that specific request, not persisted
catalog state. Successful listing does not prove that credentials were required,
that a particular model can run, or that tools/reasoning/media work. No `/health`
response is treated as authentication proof. The tool explicitly returns
`inferenceChecked: false` and `catalogChanged: false`.

### Manual configuration workflow

1. Save an enabled connection with its exact inference prefix, credential source,
   HTTP settings and execution target.
2. Invoke `grz_ai_connection_models` with that connection's ID. This is an explicit
   operation, not a side effect of saving or opening settings.
3. Select an exact returned model ID. Read its actual capabilities, limits and
   supported request behavior from the model/installation documentation or verify
   them separately. The model name alone is not evidence for those settings.
4. Use the existing model-spec editor or `grz_ai_model_spec_upsert`, then the model
   configuration editor or `grz_ai_model_configuration_upsert`. Use the connection's
   existing catalog provider identity and the exact gateway ID; copy a known spec
   only after checking that its behavior applies to that route. Respect catalog
   revisions and preserve manual settings. Listing does not overwrite any of them.

This stage adds the Control tool, not a separate discovery UI. Existing manual
catalog editors are unchanged. Tests use local HTTP/TLS and an in-process production
Worker executor with codecs; they are not acceptance of the work TrueFoundry deployment.

## Sources

- [TrueFoundry authentication](https://www.truefoundry.com/docs/ai-gateway/authentication)
- [TrueFoundry native SDK support](https://www.truefoundry.com/docs/ai-gateway/native-sdk-support)
- [TrueFoundry metadata and headers](https://www.truefoundry.com/docs/ai-gateway/request-headers)
- [HTTP Retry-After semantics (RFC 9110)](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)
- [HTTP 429 (RFC 6585)](https://www.rfc-editor.org/rfc/rfc6585.html#section-4)

- [TrueFoundry model listing contract](https://www.truefoundry.com/docs/api-reference/models/list-available-models)

## Optional token-quota admission

[TPM policies](ai-tpm.md) belong to model configurations, not connections. They
inspect the prepared JSON before native HTTP execution and retain the slot until
body completion/cleanup. Quota feedback errors never trigger an inference replay.
Multipart STT and realtime WebSocket do not support TPM. Existing HTTP transport
settings and their complete-429-only retry contract remain independent.

## Opt-in TrueFoundry JSON Schema finish-reason workaround

TrueFoundry 0.179.11 has been observed returning `finish_reason: "tool_calls"`
for a valid JSON Schema answer in `message.content`, without any tool call, when
using non-streaming Chat Completions. This is not permission to accept arbitrary
inconsistent responses or bypass required function calls.

For an affected exact model route, enable
`AiModelSpec.behavior.chatSchemaEmptyToolCallsAsStop` (default **false**). The UI
checkbox is under AI Settings → Model specs → Request behavior. It applies to
configurations using that spec. Existing
`grz_ai_model_spec_upsert`/catalog tools read and update the same field. No model
name, URL, provider credential or gateway version enables it automatically.

The OpenAI-compatible Chat adapter applies the workaround only after the SDK
successfully parses the HTTP response, with one choice whose finish reason is
`tool_calls`, and only when all of these checks pass:

- The actual sent SDK parameters contain `response_format: json_schema` with a
  schema. Tool choice is absent, `auto` or `none`, not a required/named tool.
- `tool_calls` is absent, null or an empty array. A malformed field is not empty.
- Legacy `function_call` and `refusal` are absent or null, not malformed/empty
  objects or strings. Legacy request function-choice options are also excluded.
- Content parses as one strict JSON value, with no duplicate keys, trailing JSON,
  Markdown fence removal, coercion or content repair, and validates against the
  exact schema from the sent parameters using the existing schema validator.

Only the effective finish reason becomes `stop`, producing `COMPLETE`.
`originalFinishReason: "tool_calls"` and
`finishReasonNormalization: "chatSchemaEmptyToolCallsAsStop"` are recorded in
response and persisted assistant metadata. Original content, usage and opaque
reasoning/replay fields remain unchanged. The shared
`Tool-call outcome without tool calls` invariant remains in place.

This does not enable SSE, change endpoints, retry a failed inference, alter TPM
feedback/accounting, or affect Responses/Anthropic/CLI adapters. Disable the flag
after confirming that the gateway no longer needs it. Local real-SDK HTTP fixtures
cover tool → result → schema → follow-up, negative conditions and unchanged
reasoning replay; live validation on the work deployment is still required.

Gateway protocol **25** carries the expanded model-behavior contract. Upgrade
Server, Workers and UI clients together; no persisted-data migration is needed.
The existing serialized catalog defaults this flag to false when absent.
