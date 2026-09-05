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
unavailable. No battery-policy bypass, push wakeup or guaranteed
immediate delivery is introduced. Delivery TTL continues to govern queued requests.
Only a running loud alert holds a bounded partial wake lock; an idle Gateway does not.

The choice of service type follows the
[Android foreground service types reference](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
Background restart remains subject to the
[Android startup restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
Play Store approval of this use case is not implied by sideloading support.

## Execution and persistence

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

Modern Android service restrictions, vendor power policies, reboot recovery,
long offline intervals and real network switching still require physical-device
or matching modern-emulator validation before family deployment.
The Gateway/sound smoke run and enable/play/stop UI checks passed on an isolated
API 29 emulator; Android 14+ foreground-service behavior has not yet been
runtime-tested, and audible output has not been verified on a physical device.
