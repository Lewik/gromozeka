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
an encrypted, atomic filesystem journal with an exclusive process lock. A future
mobile runtime supplies its own persistent adapter and OS lifecycle integration.
There is no new Worker session domain or desired-state reconciler.

## Timing and cancellation

Worker and Workspace tool `execution_target` accepts these optional integer fields:

| Field | Meaning | Default | Maximum |
| --- | --- | --- | --- |
| `delivery_ttl_seconds` | Time until execution may start, including offline and local queue time | 30 seconds | 7 days |
| `execution_timeout_seconds` | Execution limit after starting | 30 minutes | 24 hours |
| `wait_timeout_seconds` | How long this invocation waits for its result | TTL + execution, capped at 7 days | 7 days |

Expiry never interrupts an already started action. Deadlines use UTC clocks; device
clocks must be reasonably synchronized. Coroutine timeout and cancellation are
cooperative; platform handlers must honor cancellation and clean up their resources.

When waiting ends first, the caller receives a pending error with `requestId`. It
must query `grz_worker_request_get`, not resubmit the action. Saved tool binaries
return through normal artifact materialization. `grz_worker_request_cancel` records
cancellation even while offline. Both tools require the original author, original
Project, and current access. System requests are not exposed through these tools.

Cancelling the parent execution also records cancellation. Already performed effects
cannot be undone. A terminal result wins over late cancellation. After possible
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
mobile runtime. Android command execution through the Gateway is a separate step.

## Verification

Focused coverage lives in `WorkerRequestExecutorTest`, `WorkerGatewayRuntimeTest`,
`WorkerRequestServiceTest`, `WorkerRequestToolContributorTest`,
`JvmWorkerRequestJournalTest`, and `PostgresWorkerRequestRepositoryTest`.
The PostgreSQL test uses an isolated schema and requires
`GROMOZEKA_POSTGRES_RUNTIME_TEST=true`; it does not modify the development schema.
