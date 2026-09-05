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
unavailable. No battery-policy bypass, wake lock, push wakeup or guaranteed
immediate delivery is introduced. Delivery TTL continues to govern queued requests.

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

The first tool, `grz_get_device_status`, reads device information, battery,
airplane mode, Bluetooth availability, app storage capacity and pending event
count. It has no arguments and does not collect location or alter settings. Its
contract and dispatch code are common Kotlin; only the device probe is Android.
Sound, VPN, app management and other device tools are separate feature additions.

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

## Verification

Common tests cover tool routing, target/argument checks, cancellation, disabled
enrollment, persisted opt-in, response replay after runtime recreation, ACKs,
storage errors, journal bounds and interrupted execution recovery.
`WorkerGatewayTest` executes the actual device-status tool protocol through the
Server WebSocket endpoint. Its device probe is a fake, not an Android hardware
test.

`GatewaySmokeInstrumentation` requires a fresh test installation. It deliberately
refuses to overwrite existing Worker state. It checks Android Keystore/atomic
storage, foreground notification, independence from the Activity, and durable
disable against an unreachable loopback endpoint without contacting a real Server.

```bash
ANDROID_HOME=/path/to/sdk ./gradlew :worker-runtime:jvmTest :mobile-worker:jvmTest :mobile-worker-android:assembleDebug :mobile-worker-android:assembleDebugAndroidTest -q
adb -s <test-emulator> install mobile-worker-android/build/outputs/apk/debug/mobile-worker-android-debug.apk
adb -s <test-emulator> install mobile-worker-android/build/outputs/apk/androidTest/debug/mobile-worker-android-debug-androidTest.apk
adb -s <test-emulator> shell am instrument -w com.gromozeka.mobile.worker.test/com.gromozeka.mobile.worker.GatewaySmokeInstrumentation
```

Modern Android service restrictions, vendor power policies, reboot recovery,
long offline intervals and real network switching still require physical-device
or matching modern-emulator validation before family deployment.
The local smoke run and enable/disable UI check passed on an isolated API 29
emulator; Android 14+ foreground-service behavior has not yet been runtime-tested.
