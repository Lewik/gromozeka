# Android Worker Gateway

Android uses `WorkerGatewayRuntime` and `WorkerRequestExecutor` from
`:worker-runtime`, with the same authenticated `/worker/ws` endpoint and durable
request protocol as the desktop Worker. No mobile command queue, session domain,
or second retry algorithm is introduced.

## Enabling and stopping

After enrollment, **Enable remote commands** opts this device into a persistent
connection. The setting belongs to the enrollment, defaults to disabled, and is
cleared by removing the enrollment. Android 13+ asks for notification permission
before enabling the feature. The persistent notification and the app both offer
a disable action. Closing the Activity does not stop the Worker service.

`AndroidWorkerGatewayService` is a non-exported foreground service. Android 14+
uses `specialUse`, with its explicit remote-device-command use case declared in
the manifest. It does not claim camera, microphone, location, or device-admin
permissions. This is intended for the separately installed Worker; it is not an
invisible service inside the chat Client.

Startup occurs from the visible app or, if already enabled, boot/package-replaced
receivers. The service is sticky, but an explicit disable is durable. Force-stop,
Doze, network restrictions and vendor battery policies can still make the Worker
unavailable. No push wakeup or guaranteed immediate delivery is introduced.
Delivery TTL continues to govern queued requests.
Only a running loud alert holds a bounded partial wake lock; an idle Gateway does not.

The app shows battery optimization exemption and user-imposed background
restriction separately, refreshing them when returning from Android settings.
**Allow background connection** opens Android's explicit battery exemption
confirmation; it never changes the setting silently or makes it a prerequisite
for enrollment. The `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` manifest permission
only permits showing this request. If background work is restricted, the app
instead opens its system settings so the user can remove that restriction.
Exemption allows network access in Doze but does not guarantee CPU wakeups,
delivery through a lost network, or survival of force-stop/vendor policies.
It can increase battery usage. This is an optional user-controlled setting for the
remote automation use case, not a Worker desired-state enforcement loop.
See [Android Doze behavior and exemption use cases](https://developer.android.com/training/monitoring-device-state/doze-standby)
and [user-imposed background restrictions](https://developer.android.com/topic/performance/background-optimization).

The choice of service type follows the
[Android foreground service types reference](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
Background restart remains subject to the
[Android startup restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
Play Store approval of this use case is not implied by sideloading support.

## Execution and persistence

The Android transport uses OkHttp's engine-level connection timeout and ping
interval. Do not set Ktor `WebSockets.maxFrameSize` with this engine: Ktor 3.5.2
tries to mutate the OkHttp session's unsupported property and throws before
registration. The shared transport instead rejects messages over 1 MiB before
CBOR decoding on Android. This is a decode boundary, not a wire-level allocation
limit: OkHttp has already buffered the received message.

The Worker advertises only `TOOL_EXECUTION` and the tools it actually implements.
`WorkerToolRequestHandler` validates the stable Worker target, rejects Workspace
targets and unsupported operations, and returns ordinary tool results. The
current enrollment and opt-in flag are checked before each tool call. It does not
advertise AI execution, external MCP, shell, file access, audio capture, or Computer
Use. An assigned external MCP server causes explicit readiness failure, not an
ignored configuration presented as working.

`grz_get_device_status` reads device information, battery,
airplane mode, Bluetooth availability, app storage capacity and pending event
count. It has no arguments and does not collect location or alter settings. Its
contract and dispatch code are common Kotlin; only the device probe is Android.
`grz_play_loud_sound` is the second common tool contract, backed by Android alarm
playback. VPN, app management and other device tools remain separate additions.

`grz_get_current_location` is also a common contract, backed by the independently
enabled [Android location service](android-worker-location.md). It requires local
sharing consent and active location permission; enabling remote commands alone
does not start a location sensor. The Gateway service itself does not acquire
the location foreground-service type.

`SnapshotWorkerRequestJournal` implements durable receipt serialization in common
Kotlin. Android supplies an encrypted atomic-file store using the same Keystore
key and file writer as event storage, with an exclusive journal file lock.
Receipts belong to one enrollment stream; a replacement enrollment never reuses
the old journal. One in-process lifetime mutex prevents overlapping executors
while a stopped service finishes cancellation and persistence. The shared
executor recovers saved responses, reports `OUTCOME_UNKNOWN` for interrupted
running requests, and never automatically repeats their effects.

The receipt snapshot is bounded at 8 MiB; the shared executor also limits receipt
count to 4096. Overflow fails closed. A disk failure after atomic replacement can
be ambiguous, so operations reread committed storage instead of trusting an
uncommitted memory cache. Old enrollment journal files are retained in private,
no-backup app storage; clearing app data also removes them.

## Loud sound

**Allow loud sound** is a separate, enrollment-scoped opt-in, disabled by default.
The tool catalog lists supported tools, not permission grants: a sound request
checks the current enrollment, Gateway opt-in and sound opt-in before playback.
Disabling sound stops the current alert without disabling other commands. The
app offers **Test sound (3s)** and **Stop sound**; the foreground notification
offers the same stop action during playback. The local test uses the same tool
and output implementation without creating a remote request.
The sound tool is excluded from the memory pipeline's tool catalog.

`grz_play_loud_sound` accepts only `duration_seconds`, an integer from 1 through
60, default 10. It waits for `COMPLETED` or `STOPPED_LOCALLY`; neither means a
person heard the alert. Delivery TTL and execution timeout use the existing
Worker request envelope. The common controller rejects overlapping sounds,
waits for output cleanup on cancellation, and never introduces another queue.
Preparation has a five-second deadline; playback duration starts only after the
output signals readiness. A preparation timeout is an error, not completion.
Gateway disconnect does not cancel a running alert, and normal durable request
replay does not play it again. Process interruption still yields
`OUTCOME_UNKNOWN`, not automatic retry of an uncertain action.

Android uses generated PCM through `AudioTrack` with `USAGE_ALARM`, a finite loop
count, temporary audio focus, and a partial wake lock limited to the requested
duration plus five seconds. The existing foreground service adds `mediaPlayback`
only while an alert is active; boot starts only the `specialUse` connection.
`MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `WAKE_LOCK` are
manifest permissions. No root, device owner, microphone, or Notification Policy
Access is requested for this feature.

Alarm volume is temporarily raised to maximum. Its previous value is saved
before mutation in an encrypted atomic file. Cleanup restores it only if the
volume still equals the applied value, preserving observable manual changes.
The same common restoration code runs on the next app-process startup after a
crash; audio itself dies with the process. A user change back to the identical
maximum value cannot be distinguished from the app's override. If Android denies
restoration, the saved override remains available for a later startup.
If DND hides the real volume or an external output changes the route, restoration
is deferred instead of guessing or adjusting another output. It is retried on
the next app open/process startup or sound request with an inspectable speaker
volume. This is cleanup of one override, not continuous settings enforcement.

Silent ringer mode is not the alarm stream. DND remains under user control: allow
alarms in Android settings. The implementation never turns off global DND or
creates an automatic rule. Android 11+ checks the consolidated alarm policy;
older Android rejects priority-only DND conservatively, accepting normal mode
and alarms-only mode. Known blocked DND, denied audio focus, a call, mute, fixed
volume, or an unavailable speaker produces an error. Output is initially muted
until the actual speaker route is checked. Connected headphones/external outputs
are rejected; connecting one during playback mutes/stops the alert. Android 16+
can report multiple routed devices; older versions expose only one, so physical
routing and OEM behavior still require device validation.

The DND restriction follows the
[Android 15 behavior changes](https://developer.android.com/about/versions/15/behavior-changes-15):
Notification Policy Access no longer lets ordinary apps disable another owner's
global DND state. See also
[consolidated policy](https://developer.android.com/reference/android/app/NotificationManager#getConsolidatedNotificationPolicy())
and [AudioTrack routing](https://developer.android.com/reference/android/media/AudioTrack#setPreferredDevice(android.media.AudioDeviceInfo)).

## Verification

Common tests cover tool routing, target/argument checks, cancellation, disabled
enrollment, persisted opt-in, response replay after runtime recreation, ACKs,
storage errors, journal bounds and interrupted execution recovery. Sound tests
cover arguments, duration, overlap, local stop, request cancellation/timeout,
output failure, durable result replay, volume persistence/restoration and manual
volume changes.
`WorkerGatewayTest` executes the actual device-status tool protocol through the
Server WebSocket endpoint. Its device probe is a fake, not an Android hardware
test.

`GatewaySmokeInstrumentation` requires a fresh test installation. It deliberately
refuses to overwrite existing Worker state. It checks Android Keystore/atomic
storage, foreground notification, independence from the Activity, and durable
disable against an unreachable loopback endpoint without contacting a real Server.
It also exercises the real Android sound output with opt-in denied/allowed,
notification stop, bounded playback, volume restoration and preservation of
manual volume changes. Run it only on a disposable emulator: it changes alarm
volume. On API levels below 35 it temporarily uses the test shell's policy access
to enable global DND, revokes that access, verifies refusal without changing DND,
interrupts an active alert and checks deferred volume restoration on app reopen,
then restores the original setting. That scenario is not a modern-DND validation.
Headless runs with `-no-audio` verify Android state, not acoustic output.

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :worker-runtime:jvmTest :mobile-worker:jvmTest :mobile-worker-android:assembleDebug :mobile-worker-android:assembleDebugAndroidTest -q
adb -s <test-emulator> install mobile-worker-android/build/outputs/apk/debug/mobile-worker-android-debug.apk
adb -s <test-emulator> install mobile-worker-android/build/outputs/apk/androidTest/debug/mobile-worker-android-debug-androidTest.apk
adb -s <test-emulator> shell am instrument -w com.gromozeka.mobile.worker.test/com.gromozeka.mobile.worker.GatewaySmokeInstrumentation
```

### Modern Android lifecycle test

`AndroidWorkerLifecycleTest` is an opt-in host JUnit test controlling one explicitly
selected, disposable API 35+ emulator. It validates APK package identities before
installation and uses only `com.gromozeka.mobile.worker.lifecycle`, clearing that
test application's data. It never selects a default ADB device or uses real
enrollment credentials. The fixture is an actual TLS endpoint and CBOR Worker
Gateway with fake catalog/event ACKs; it needs neither PostgreSQL nor AI services.
It occupies loopback ports 18876 and 18877, which must be free.

```bash
ANDROID_HOME=/path/to/sdk bash scripts/test-android-worker-lifecycle.sh emulator-5582
```

The script creates a throwaway two-day TLS certificate, builds an opt-in
`lifecycle` variant, and runs the test. Only that variant trusts the test CA for
`10.0.2.2`; normal debug/release trust and HTTPS enforcement remain unchanged.
The printed temporary directory retains the small disposable certificate fixture
for diagnosis. The emulator must already be running; the script does not start
or modify other AVDs. The test changes power/network state and alarm volume,
updates its isolated APK, and reboots the emulator repeatedly. Run it on no other
environment. It restores its power/network test overrides in `finally`.
The suite also includes the location scenarios described in the linked location
document, using a controlled GPS test provider and the same isolated TLS fixture.

The matrix covers screen-off commands/sound, offline queued requests and TTL,
emulated cellular/Wi-Fi handover, package replacement, reboot without app launch,
Doze/wake recovery, explicit battery exemption, force-stop/manual launch, and
durable user disable across reboot. The ordinary Doze scenario removes the
temporary `BOOT_COMPLETED` exemption and records the actual UID network policy
and whether delivery occurred before wake. On the Android 16 image tested here,
the running foreground service itself retained network access without a battery
exemption (`allowed=FOREGROUND`, `effective=NONE`). A forced-idle emulator does not
fully reproduce physical CPU suspend or manufacturer power policies. The test
therefore checks recovery without assuming every Android build must delay delivery.

Physical speaker output, long-term battery consumption, real mobile networks
and manufacturer-specific policies still require checks on the actual phones
before family deployment. Emulator success does not certify those behaviors.

The full lifecycle matrix passed on 2026-09-05 on an isolated Google APIs ARM64
Android 16/API 36 emulator with the application's current target SDK 35.
Native UI checks also verified battery exemption denial/approval, status refresh
on return from settings, and the separate background-restricted state.
`GatewaySmokeInstrumentation` also passed on the normal debug APK on that image,
including native sound playback state, stop and volume restoration.
