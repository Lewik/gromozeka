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

Worker Gateway protocol 19 carries the HTTP execution settings, logical deadline
and typed AI failures (protocol 18 introduced HTTP settings and the file secret kind). Upgrade Server, Worker **and UI clients** together. Older clients cannot
preserve the new fields or decode the new secret kind; mixed-version editing is
not supported.

This applies to the three finite inference APIs, not embeddings/audio, subscription
backends, Bedrock execution, or a new streaming contract. Discovery, health and
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

## Sources

- [TrueFoundry authentication](https://www.truefoundry.com/docs/ai-gateway/authentication)
- [TrueFoundry native SDK support](https://www.truefoundry.com/docs/ai-gateway/native-sdk-support)
- [TrueFoundry metadata and headers](https://www.truefoundry.com/docs/ai-gateway/request-headers)
- [HTTP Retry-After semantics (RFC 9110)](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)
- [HTTP 429 (RFC 6585)](https://www.rfc-editor.org/rfc/rfc6585.html#section-4)
