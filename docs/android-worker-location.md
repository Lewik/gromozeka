# Android Worker location

Location sharing is an independent, local, enrollment-scoped opt-in. Android
permission alone never enables it. Removing enrollment clears consent; turning
off remote commands does not turn off tracking. Turning off tracking rejects
new sensor callbacks and location commands, but does not erase already recorded
history or the pending delivery queue.

`WorkerLocationConfiguration`, `WorkerLocationSample`, freshness validation and
`grz_get_current_location` live in common Kotlin in `:worker-runtime`.
`MobileWorkerRuntime` persists configuration in the encrypted enrollment state.
An enrollment stream and configuration revision fence delayed observations at
the same atomic storage boundary as outbox appends. Disabling and re-enabling
sharing cannot admit a callback from the previous collection.

## Device controls and collection

The Android UI exposes location permission, optional background permission,
interval, movement threshold, enabled state, last measurement time/accuracy,
delivery status and local disable. Defaults are 60 seconds and 25 meters;
both thresholds apply. These are minimum sampling thresholds, not a promise of
a fix every minute. Short intervals can substantially increase battery use.
Exact coordinates are not written to diagnostic logs or notifications.

`AndroidWorkerLocationService` is a non-exported `location` foreground service
with its own visible notification and disable action. This is a platform
lifecycle boundary inside the same Worker app, identity and shared runtime,
not another Worker kind or transport. Gateway and location have independent
opt-ins and different Android service-type requirements.

The source uses Android `LocationManager`, without a Google Play Services SDK
dependency. It prefers enabled GPS with precise permission, then network, then
framework fused location where available. Approximate permission is accepted;
reported accuracy remains visible. Disabled providers pause acquisition and are
retried while the sharing service remains running. Denied permission, blocked
notifications or Android removing foreground status stop the service with an
explanation; the app never changes those settings itself.

Sharing can start from the visible Activity with foreground location permission.
Android 10+ background location permission is separately requested/explained for
restart without opening the app. On Android 11+ the user grants it through app
settings. Boot/package replacement start only previously enabled sharing with
background permission. Force-stop still requires manual launch, and vendor
power policies can interrupt collection. See [Android location foreground
services](https://developer.android.com/develop/background-work/services/fgs/service-types#location)
and [background location permission](https://developer.android.com/develop/sensors-and-location/location/permissions/background).

## Events and current-location commands

Every accepted sample enters the existing durable Worker outbox before delivery.
While tracking, the service requests immediate synchronization after a sample or
network reconnection, retries after 30 seconds when needed, and drains bounded
batches through the shared HTTP/ACK implementation. JobScheduler remains the
fallback when tracking is not running. There is no new event transport or queue.
The existing 10,000-event/8 MiB limit still applies; overflow preserves pending
events and explicitly reports that new points cannot be stored. Offline storage
is bounded, not unlimited.

`grz_get_current_location` uses the ordinary Worker request lifecycle and accepts:

- `max_age_seconds`: 0 by default, requiring a measurement taken after acquisition
  starts. An explicit value through 3600 permits a cached measurement of that age.
- `timeout_seconds`: 1–120, default 30, for sensor acquisition.

Delivery TTL governs when the request may start; request execution timeout also
applies and may be shorter than acquisition timeout. Configure both deliberately.
Cancellation removes the listener. No acceptable fix results in an error, never
a fallback to stale coordinates. Freshness uses Android monotonic measurement
time; `observedAt` preserves the measurement's wall-clock timestamp for history.
Successful command results are also recorded with cause `CURRENT`; periodic
samples use `LIVE_TRACKING`. Replay of a completed request returns its original
measurement, not a new fix. Use a new request to obtain a new position.

Location commands require both enabled remote commands and active local sharing,
and are fenced to the Gateway's enrollment. They cannot secretly enable tracking.
The location tool is excluded from memory pipelines.

The existing Server `get_device_state` and `query_state_history` tools expose
reported location, observation/receipt timestamps and accuracy. They require
an authenticated active user with `USE` permission on the exact Worker. Event
ownership comes from its credential and approved user binding. Out-of-order
offline events enter immutable history without replacing a newer current point.
Maps, geofences, retention policy and iOS/desktop sensor adapters are not added
by this milestone.

## Verification

Common tests cover argument/configuration bounds, freshness, timeout/cancellation,
persisted opt-in, independent Gateway disable and stale callbacks after consent
changes or reenrollment. Server route tests validate location ingestion and user
binding. `PostgresWorkerEventRepositoryTest` verifies location history, duplicate
delivery, current projection ordering, timestamps and user isolation in a
temporary schema when `GROMOZEKA_POSTGRES_RUNTIME_TEST=true`.

The opt-in Android lifecycle suite includes a location scenario:

```bash
ANDROID_HOME=/path/to/sdk bash scripts/test-android-worker-lifecycle.sh emulator-5582
```

To run only location, set
`ANDROID_LIFECYCLE_TEST_FILTER='com.gromozeka.server.AndroidWorkerLifecycleTest.location*'`.
The test drives native consent/disable controls and the real LocationManager
callback path with a controlled test GPS provider on a disposable emulator.
Unlike the emulator GNSS HAL, the test provider does not continuously generate
new fixes at unchanged coordinates, allowing a deterministic stale-cache test.
It covers screen-off offline route delivery, fresh/cached requests, reboot,
permission revocation, independent Gateway disable and durable sharing disable.
It restores networking, removes the test provider and force-stops its isolated
application. Baseline background app-op is explicitly allowed: a previous
restriction experiment must not leak into the next run.

The host fixture checks actual HTTPS batches and CBOR commands with fake event
ACKs; PostgreSQL and authenticated Server routes are exercised separately.
Emulator tests do not certify real GPS reception, physical CPU suspend, mobile
network behavior, manufacturer policies or day-long battery consumption. Those
still require testing on the intended phones before family use.

On 2026-09-05 the complete lifecycle suite and the focused location rerun passed
on the Google APIs ARM64 Android 16/API 36 emulator (target SDK 35), along with
common tests, Server route/Gateway tests, the real PostgreSQL test and normal
debug sound instrumentation. Native visual checks covered permission denial,
approximate permission without automatic opt-in, active tracking status and
the notification's persisted disable action.
