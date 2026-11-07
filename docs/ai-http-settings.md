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
reused; that token stays fixed during the call's current SDK retries. This trades
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
  lookups or substring matching. These settings do not introduce URL redirects;
  other SDK transport defaults remain unchanged.
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

Worker Gateway protocol 18 carries the new connection fields and file secret
kind. Upgrade Server, Worker **and UI clients** together. Older clients cannot
preserve the new fields or decode the new secret kind; mixed-version editing is
not supported.

This is configuration support for the three finite inference APIs, not an
embeddings/audio integration or a new streaming contract. Cancellation,
connection-wide concurrency, unified retry budgets and typed error propagation
through Worker transport remain separate work. SDK retry policies are unchanged
here. Discovery, health and target-installation acceptance are also separate.

## Sources

- [TrueFoundry authentication](https://www.truefoundry.com/docs/ai-gateway/authentication)
- [TrueFoundry native SDK support](https://www.truefoundry.com/docs/ai-gateway/native-sdk-support)
- [TrueFoundry metadata and headers](https://www.truefoundry.com/docs/ai-gateway/request-headers)
