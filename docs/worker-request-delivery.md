# Worker request delivery

Server-to-Worker requests address one stable `workerId` and carry one stable
`requestId`. The Gateway connection is transport, not request ownership. Separate
requests may execute concurrently on one Worker or on different Workers. Existing
operation-specific serialization, such as actions on one display, remains intact.

## Lifecycle

1. Server authorizes and commits the encrypted request to PostgreSQL before dispatch.
2. Each connection delivers pending IDs, rechecking current User/Project/Worker access.
3. Worker persists a receipt before execution and marks it running before invoking
   the handler. Duplicate delivery with the same ID does not run another action.
4. Worker persists the response before sending it. Server persists the first terminal
   response before acknowledging it. Lost responses and acknowledgements are retried.
5. Worker removes the response payload on acknowledgement and keeps its receipt until
   the start deadline. Later delivery of an expired, forgotten ID cannot execute.

An interrupted connection does not cancel an executing request. A Worker process
restart recovers saved responses. A receipt left running becomes `OUTCOME_UNKNOWN`,
because external side effects cannot be committed atomically with the local journal.
Such an action is never automatically repeated. This is not an exactly-once guarantee
for external effects. Keep the Worker's journal and identity together; deleting its
state is not an ordinary restart.

The common executor and Gateway runtime live in `:worker-runtime`. Desktop supplies
an encrypted, atomic filesystem journal with an exclusive process lock. Android
uses the common snapshot journal with its Keystore/atomic-file adapter and an
[explicit foreground-service lifecycle](android-worker-gateway.md).
There is no new Worker session domain or desired-state reconciler.

## Tool invocation context

Tool declarations separate execution scope, context data requirements and access policy.
`AiToolMetadata.contextRequirements` declares mandatory and conditionally used context;
conditional business validation remains in the operation. A Server tool does not implicitly
require a Conversation, and a Conversation never supplies an implicit filesystem mount.

The common schema builder exposes a flat reserved `_grz` object only for relevant selectors
not already bound by the entry point. Examples for an unbound authenticated caller:

- Rename: `{"display_name":"New title","_grz":{"conversation_id":"…"}}`.
  Runtime-bound rename needs only `display_name`.
- Read file: `{"file_path":"README.md","_grz":{"workspace_mount_id":"…"}}`.
  This is also required inside a Conversation unless a mount is explicitly bound.
- Global Control catalog: `{}`. Its authenticated-user and Server-owner gates do not
  imply Conversation/project/Worker selectors.

`_grz` is stripped before business execution. Business targets such as pin-tools'
`conversation_id` remain outside it. User identity, memory namespace, thread, filesystem
root and tool-call identity cannot be supplied in `_grz`. A selected Conversation derives
its project/current thread; a selected mount derives its project/Worker/root. Matching
bound selectors may be echoed, but a binding cannot be replaced. Parsing alone never
promotes a selector to trusted context: Server resolution checks resource consistency
and access, and final operation authorization/tool policy and approval still apply.

## Unified HTTP MCP

`POST /mcp` publishes Server-native tools, Control tools and tools advertised by accessible
Workers (including their external MCP connections). It uses the existing native provider,
immutable contract/name registry, context resolver, approval/secret handling and Worker
request delivery. There is no second registration system and no implicit Conversation,
Agent, filesystem mount or model turn. Use `tools/list` to discover current versioned names
and schemas; do not guess an old logical name after a contract changes.

Authentication accepts the existing HTTPS browser session, a bearer PAT with **`mcp:control`**,
or an opaque MCP OAuth access token when the explicit OAuth issuer is configured. OAuth
uses Authorization Code + PKCE-S256, CIMD registration and rotating refresh tokens; see
[the Server OAuth profile](development-guide.md#external-mcp-oauth). Explicit bearer
credentials never borrow a valid browser cookie on failure. The PAT format, lifetime,
storage and scopes are unchanged in this phase; an old `mcp:memory`-only token does not gain
tool access. User/resource authorization remains shared. `/mcp/control` is removed, not an
alias. `GET` and `DELETE /mcp` are unsupported by this stateless transport.

Memory tools and `memory_help` are absent. The legacy memory-only HTTP adapter and its
publication settings (`GROMOZEKA_MCP_EXPOSED_TOOLS`, `gromozeka.mcp.exposed.tools`,
`gromozeka.mcp.memory-tools-enabled`) are retired; wildcard/opt-in settings cannot reopen it.
Application/domain memory, its native callbacks and global memory settings are not changed.

A Worker tool accepts an optional `_grz.project_id` when access uses a project grant rather
than a direct user grant. A workspace mount already derives its project. Access is checked
again on each call; listing never starts a Worker, creates a task or grants lasting authority.
Selecting an Agent additionally checks its current tool policy against the shared contract.
The HTTP dispatcher binds the selected native descriptor fingerprint to the trusted call ID;
Server and Worker executors reject a changed callback before approval/effects, including a
Worker upgraded while the request was queued. No logical-name fallback or automatic retry.
HTTP calls return native text/binary content, `structuredContent` and `isError`; they do not
append a Conversation message or automatically wake a target agent.

Example JSON-RPC bodies (replace names/IDs with the current `tools/list` and resource catalog):

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"grz_project_list","arguments":{}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"grz_read_file","arguments":{"file_path":"README.md","_grz":{"workspace_mount_id":"<mount-id>"}}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"grz_conversation_rename","arguments":{"display_name":"New title","_grz":{"conversation_id":"<conversation-id>"}}}}
```

Send `Authorization: Bearer <PAT>`, `Content-Type: application/json` and
`Accept: application/json, text/event-stream` to the HTTPS endpoint. Perform the ordinary MCP
initialize handshake and use the negotiated protocol version. These examples do not create
another scripting language or execute code on Server; filesystem/shell operations target a Worker.

There is no `execution_target` compatibility alias. V74 invalidates the derived contract
registry; it does not rewrite messages, cached tool calls or exact-revision access policies.
Tools and exact-revision selections must be reinitialized after the coordinated update.

## Shared Python and shell SDK

Desktop/standalone Workers ship `grz.py` and `grz.sh` with the application. Each managed
command receives `GRZ_SDK_HOME`, pointing to the same content-addressed directory under
`GROMOZEKA_HOME/script-sdk/<sha256>`, and that directory is prepended to its `PYTHONPATH`.
The bundled files are materialized atomically, reused without rewriting, and not removed
when a command ends (including resumable commands). No user script is required: ordinary
multiline shell, Python stdin/heredoc, and saved script files all use the same library.
There is no script registry, new LLM tool, venv, pip install, daemon or interpreter download.
Python **3.9+ must already be installed**; `GRZ_PYTHON` selects an existing executable for
the shell wrapper without changing `PATH`. The wrapper is POSIX `sh`/Bash, not Windows
cmd/PowerShell; Python and the shared path are also available on Windows.

Authentication is **explicit**, not inherited from Worker Gateway enrollment. Set
`GRZ_MCP_URL` to the Server HTTPS `/mcp` endpoint and `GRZ_MCP_TOKEN` to an existing
`mcp:control` PAT, preferably using the existing named-secret substitution:

```sh
export GRZ_MCP_URL=https://your-server.example/mcp
export GRZ_MCP_TOKEN=secret://my-mcp-pat
python3 - <<'PY'
from grz import Client
with Client.from_env() as client:
    tools = client.list_tools()
    print([tool["name"] for tool in tools])
    # Use an EXACT name and its inputSchema from this list; do not guess __vN.
    # result = client.call(exact_name, arguments)
PY
```

`secret://…` substitution applies when the command is submitted to Gromozeka, not when
running an arbitrary terminal script. Never put a token in a saved script, command argument,
log, or checked-in environment file. No PAT is minted automatically; its existing scope,
lifetime and user authority are unchanged. This is the normal full-host command environment,
**not a sandbox or a way to constrain a PAT's authority**. Do not provide credentials to
untrusted scripts. The SDK does not perform OAuth login/refresh or access Keychain.

Shell uses the same Python core (one helper process per call in this version):

```sh
. "$GRZ_SDK_HOME/grz.sh"
grz_list_tools
grz_describe_tool "$exact_name"
grz_call "$exact_name" '{"some_argument":"literal value"}'
# Or supply the argument object on stdin, useful for multiline JSON:
grz_call "$exact_name" <<'JSON'
{"some_argument":"line one\nline two"}
JSON
```

Python offers `Client(url, token, context=..., timeout=60)`, `Client.from_env()`,
`list_tools(refresh=True)`, `describe_tool(exact_name)`, and
`call(exact_name, arguments, context=..., check=True)`. It is synchronous, not thread-safe.
Defaults in `GRZ_MCP_CONTEXT` come from that command's project/conversation/agent/mount/Worker;
no user ID, thread, filesystem root, tool-call ID or credentials are exported there.
Only selectors declared by the chosen tool's `_grz` schema are forwarded. Explicit
`context={...}` or `arguments['_grz']` **replaces** defaults rather than mixing two scopes;
`context={}` disables them. Selectors are routing convenience, not trusted authorization:
Server checks access and exact contracts on every invocation. A cached tool listing is not
a permission grant; refresh is explicit, and names are never rebound to another version.

Results retain the complete MCP object, including text/binary content, `structuredContent`,
`isError`, and native `PENDING`/task receipts. Python raises `ToolError` with `.result` for
`isError=true` (`check=False` returns it). Shell emits that result JSON on stdout and exits 2;
transport/protocol/configuration errors exit 1 with a bounded stderr diagnostic. Success exits
0, **including PENDING receipts, which do not mean the action completed**. There are no
automatic tool retries, polling, cancellations or resumed SSE requests. On uncertain delivery,
exceptions expose `outcome_unknown`; inspect the existing request/task when its ID is known,
or verify the effect before considering another mutation. Closing the client does not cancel
a Worker action. HTTP timeout is a socket timeout, separate from native delivery/execution/wait
policy. Responses are bounded (default 16 MiB, configurable explicitly).

This small stdlib-only implementation targets Gromozeka's **stateless HTTP MCP** profile:
initialize, notifications/initialized, tools/list pagination and tools/call; JSON and POST SSE
responses; current protocol version 2025-11-25, without legacy negotiation fallback. It is not a general MCP
SDK, stdio bridge, sampling implementation or server-request executor. TLS uses system trust;
redirects, ambient proxies and cookies are not used. Plain HTTP is allowed only on loopback.

`LocalScriptSdkTest` checks materialization and executes the bundled Python unittest fixture
(20 cases, synthetic loopback server; shell case skipped on Windows). Command-service and
real process-runner tests cover per-launch routing, secret separation and path injection.
Use `GRZ_TEST_PYTHON` to select the test interpreter; these tests never install one.

Real process verification (2026-10-24, source `7a65e43e1`): isolated PostgreSQL 18.6,
Server and Worker boot JARs, fresh V75 database and synthetic user/PAT. The packaged SDK
(not a mock) discovered 132 tools, performed a nested Worker text write and byte-exact
binary read, returned a real `PENDING` receipt without retry (one actual command effect),
and renamed its Conversation through automatic context. Python stdin and saved-file paths,
POSIX inline and saved Bash paths all passed. Named-secret substitution kept the PAT out
of the stored command; missing credentials and revoked PAT failed explicitly. No model,
Keychain, production registry, deployment or installed-client update was involved. Own
Server/Worker/PostgreSQL processes stopped, private state was removed and assigned ports
were free after cleanup. Local run artifacts: `build/verification/script-sdk/`.

## Timing and cancellation

Worker and Workspace tool `_grz` accepts these optional integer fields:

| Field | Meaning | Default | Maximum |
| --- | --- | --- | --- |
| `delivery_ttl_seconds` | Time until execution may start, including offline and local queue time | 30 seconds | 7 days |
| `execution_timeout_seconds` | Execution limit after starting | 30 minutes | 24 hours |
| `wait_timeout_seconds` | How long this invocation waits for its result | TTL + execution, capped at 7 days | 7 days |

Expiry never interrupts an already started action. Deadlines use UTC clocks; device
clocks must be reasonably synchronized. Coroutine timeout and cancellation are
cooperative; platform handlers must honor cancellation and clean up their resources.

When waiting ends first, Runtime reports a pending request; HTTP MCP returns a structured
`status: PENDING` result with `request_id`. This is not a completed action or a cancellation.
Query `grz_worker_request_get`, never resubmit the action. Saved results retain binary content,
structured content and each tool's error flag. `grz_worker_request_cancel` records cancellation
even while offline. Both tools require the original author and current access using the
request's original project, if any. An unbound caller does not invent a project; an explicitly
selected project must match the original. System requests are never exposed.

Cancelling the Server execution coroutine also records cancellation. A client disconnect
or abandoning an HTTP wait is not proof that this Server coroutine was cancelled. If the
outcome is unknown, inspect/cancel the existing request; never retry the original mutation.
Already performed effects cannot be undone. A terminal result wins over late cancellation. After possible
dispatch, Server never invents a terminal expiry: Worker may already have started,
so its receipt determines the result when it reconnects.

## Bounds and scope

Server accepts at most 256 outstanding live or possibly dispatched requests per
Worker. Expired or cancelled undispatched rows do not occupy those queue slots.
Worker has 64 execution slots and at most 4096 retained receipts. Full storage fails
closed before starting unrecorded work. Request payloads are limited to 64 MiB.
Server currently retains terminal requests/results; there is no age-based cleanup.
Unacknowledged Worker results also remain durable. Retention and disk budgets need
an explicit policy before high-volume device telemetry is introduced.

General finite requests tolerate a different Worker process. Audio streams and
Computer Use observation references still belong to their originating process.
Shell process/task monitoring keeps its existing domain and recovery mechanisms;
Gateway retries do not create another shell task.

Worker-to-Server state RPC remains connection-scoped. Device observation events
use the [shared durable event outbox](worker-event-delivery.md), now adopted by the
mobile runtime. Android now receives device commands through the same Gateway;
individual device capabilities are added independently.

## Verification

Focused coverage lives in `WorkerRequestExecutorTest`, `WorkerGatewayRuntimeTest`,
`WorkerRequestServiceTest`, `WorkerRequestToolContributorTest`,
`JvmWorkerRequestJournalTest`, and `PostgresWorkerRequestRepositoryTest`.
The PostgreSQL test uses an isolated schema and requires
`GROMOZEKA_POSTGRES_RUNTIME_TEST=true`; it does not modify the development schema.

Unified MCP coverage adds `GromozekaMcpToolServiceTest`, `GromozekaStatelessMcpHttpTest`,
`GromozekaMcpToolExposureTest` and the typed-result `WorkerGatewayClientTest` regression.
The Server fixture uses actual HTTP/catalog/resolver/executor and persisted request-service
transitions with synthetic identities and in-memory storage; the Worker operation handler is
covered separately. This is not deployed-network, real filesystem, PostgreSQL or model e2e.
