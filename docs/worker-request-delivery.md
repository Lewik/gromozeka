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
