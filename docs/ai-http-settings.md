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
  "baseUrl": "https://gateway.example.com/api/llm",
  "apiKey": { "secretType": "file", "path": "/etc/gromozeka/gateway-token" },
  "httpSettings": {
    "authentication": "BEARER",
    "caBundlePath": "/etc/gromozeka/company-ca.pem",
    "proxyUrl": "http://proxy.example.com:8080",
    "noProxy": ["localhost", ".internal.example"],
    "maxConcurrentCalls": 4,
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

## Responses auto-compaction

The OpenAI Responses connection (`OPENAI_API`), including a gateway base URL,
passes the model spec's configured auto-compaction threshold as
`context_management: [{"type":"compaction","compact_threshold":...}]` on normal
`POST /responses` calls. `store=false` remains enabled. This is native provider-side
compaction, not incremental `previous_response_id` chaining, truncation, or a
separate Gromozeka summarization model.

Configure the existing model spec policy explicitly, for example this limits fragment:

```json
{
  "textGeneration": {
    "contextWindowTokens": 200000,
    "autoCompaction": { "type": "percent", "value": 70 }
  }
}
```

An absolute threshold (`{"type":"absolute","tokens":140000}`) is also supported.
`contextWindowTokens` alone does not enable compaction: an absent `autoCompaction`
policy remains disabled. Leave space below the model's actual context limit for
new tool output and generation. A single oversized input or a conversation that
already exceeds that limit may still be rejected; no history is silently truncated.
The exact provider/model route and gateway version must support this Responses
parameter and compaction-item replay. Unsupported routes fail explicitly rather
than retrying without compaction. Chat Completions is not enabled by this change.

Encrypted output items are persisted as full context checkpoints in their original
output order. Subsequent requests send the latest checkpoint and following turns,
including tool call/result pairs, without resending the covered archive. Canonical
source messages remain stored. Checkpoint-only output continues the agent loop;
malformed or wrong-connection/model checkpoints are explicit errors. Billing usage
is retained, but pre-compaction input usage is not presented as the retained-window
size on that response. Update the Server or Worker that executes the AI calls.

Regression coverage uses the real SDK and a local HTTP gateway fixture, JSON
persistence/replay and sparse-history selection. It is not acceptance testing of
a live TrueFoundry installation. Cross-provider migration is unchanged; use an
explicit handoff when changing providers.

Contract: [OpenAI Responses compaction](https://developers.openai.com/api/docs/guides/compaction).

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

A new owned SDK client is created and closed for each logical inference call.
The selected file is reread before that call, even when the runtime object is
reused; that token stays fixed across the call's controlled HTTP attempts. This trades
cross-call connection-pool reuse for straightforward credential rotation and
resource ownership. Catalog secret edits are picked up when the next runtime
context resolves the updated catalog; no application restart is needed. Updating
an unrelated shell's environment cannot change an already running JVM's
inherited environment. Use file or catalog rotation for that case.

JWT-shaped tokens with a numeric `exp` can produce a log warning when their
unverified expiry is past or within five minutes. This is not signature checking,
authentication validation, or grounds to reject a call. Other tokens are passed
unchanged. No token or decoded private claims are printed. A 401 means rejected
authentication, not necessarily expiry; a 403 is reported separately as access
denial. Neither triggers an automatic login or token refresh.

## TLS, proxy and headers

- `caBundlePath` is an absolute executor-local path to additional X.509 trust
  certificates. These supplement JVM/system trust; they never replace it or
  disable TLS/hostname checks. Bad or missing bundles fail before HTTP. The
  bundle is loaded when the per-call client is created.
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

Worker Gateway protocol 20 adds connection-scoped model listing on the exact Worker.
Protocol 19 introduced HTTP execution settings, logical deadlines and typed AI
failures; protocol 18 introduced HTTP settings and the file secret kind. Upgrade Server, Worker **and UI clients** together. Older clients cannot
preserve the new fields or decode the new secret kind; mixed-version editing is
not supported.

This applies to the three finite inference APIs, not embeddings/audio, subscription
backends, Bedrock execution, or a new streaming contract. Explicit model discovery is described below. Health endpoints and
target-installation acceptance remain separate. Memory orchestration has not
been changed; its legacy retry wrapper is outside this execution contract and
must not be assumed to honor typed terminal failures if it is enabled later.

## Execution budget, concurrency and cancellation

- `maxConcurrentCalls`: 1–64, default 4. All model configurations and runtime
  instances on the same connection/executor share a process-local gate. Different
  connections/executors are independent, even if they use the same gateway URL.
  This is not a distributed account quota or tokens-per-minute limiter.
- The permit covers client/credential setup, every HTTP attempt and retry delay.
  Cancellation or deadline expiry while queued starts no HTTP and releases no
  other call's permit. The gate is not replaced on a catalog edit: in-flight
  calls retain their snapshot, new calls use the newly resolved limit and see
  existing active calls. Lowering a limit does not cancel existing work; old
  runtime snapshots do not acquire new configuration automatically.
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

Only a completely received HTTP 429 rejection can be retried. There is no retry
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
CA trust and proxy policy from the connection. It shares the connection's concurrency
gate and has one 30-second overall deadline, including Worker delivery and queueing.
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
