# Worker event delivery

Device observations use one Worker protocol on all platforms. A Worker must have
an approved User binding to report context; the Server derives its Worker and
User identity from the credential, never from event fields. Both desktop and mobile
identities are accepted by `/api/worker/events` and `/api/worker/heartbeat`.
There is no mobile-only event source or separate transport contract.

## Shared core

`:worker-runtime` owns `WorkerEventOutbox`, `WorkerEventOutboxStore`, and
`WorkerEventClient`. Platform storage atomically persists the outbox snapshot;
the common core owns appending, unchanged-state suppression, batches, and ACKs.
`update` must serialize all writers and persist before returning. A transform
failure leaves the previous snapshot intact. Failed writes must never expose
uncommitted state through an in-memory cache; after an ambiguous disk failure,
the next operation rereads committed storage. Instances sharing a
store must also share the synchronization mutex. Storage mutation never holds
a lock across HTTP delivery.

Events receive immutable IDs and observation timestamps before persistence.
Delivery is at least once. A dropped connection, lost ACK, cancellation, or
process restart leaves unacknowledged events in the outbox. Server deduplication
is scoped to the Worker and verifies identical content. A batch commits in one
PostgreSQL transaction; a conflicting duplicate rolls the entire batch back.
Older observations enter history without replacing newer state projections.

Only an ACK covering exactly the submitted IDs removes those events. New events
can be appended during delivery and are preserved when that ACK arrives.
Accepted and duplicate ID sets must be disjoint. A random enrollment stream ID
fences local ACKs after reset or switching Servers; old data and credentials are
never reused for the new enrollment.

Unchanged state values may be suppressed, but location samples are retained even
when coordinates match. Suppression tracks observation time so out-of-order
events cannot regress the local cache. The bounded cache is an optimization,
not a replacement for pending events.

## Bounds and failures

- Up to 10,000 pending events and 8 MiB of serialized state by default.
- Up to 64 KiB per event, measured in UTF-8 bytes.
- Batches contain at most 100 events and 192 KiB of event JSON, leaving space
  for metadata below the Server's 256 KiB request limit.
- One synchronization drains at most five batches; the platform schedules
  another pass if necessary. Events have no delivery TTL.
- The latest-value cache retains at most 256 keys.

Overflow rejects the entire append without dropping existing history. HTTP and
invalid ACK errors preserve the queue. Authentication/protocol problems require
operator correction; there is no silent discard or legacy transport fallback.
A full queue still drains after an app update: new device metadata can wait
until the existing backlog creates room.

Server history and contact records currently have no retention policy. The
outbox bound is not a Server storage bound. Requests/results and device-event
history remain separate persistence concerns.

## Mobile integration

`MobileWorkerRuntime` uses the shared outbox inside its persisted enrollment
state. Android encrypts that snapshot using Android Keystore, fsyncs a temporary
file, and atomically replaces the committed file in the no-backup directory.
It does not use the SharedPreferences memory cache for queue state: a failed disk
commit must not expose an uncommitted queue snapshot to subsequent operations.
iOS keeps its existing protected atomic-file adapter; its shared Kotlin
runtime uses the same queue algorithm. The old mobile queue layout is deliberately
unsupported: existing development installations must reset and enroll again.
Server migration V51 renames Worker contact tables and normalizes stored source
types while retaining existing event IDs and history.

Android receivers persist observations and enqueue a synchronization signal,
without waiting on networking inside the broadcast lifetime. A signal is needed
only when the durable queue transitions from empty to nonempty. JobScheduler
processes signals with a network constraint; persisted retry and periodic jobs,
plus boot/package-replacement scheduling, recover interrupted delivery.
`JobWorkItem` draining lets Android close an idle job atomically with concurrent
enqueueing. Successful work-item drains do not call `jobFinished`, avoiding the
documented lost-wakeup race. [Android JobParameters reference](https://developer.android.com/reference/android/app/job/JobParameters)

Android decides when background jobs may run; network availability is a scheduling
constraint, not a promise of immediate execution. Foreground/manual sync remains
available. There is no foreground tracking service, new sensor permission flow,
push delivery, or Android command handler in this change.

The core can be reused by a desktop observation producer. This step does not add
desktop sensors or convert existing Worker-to-Server state RPC into events.
