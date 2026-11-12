# Private application updates

The Server controls updates for installed macOS ARM64 and Windows x64 clients
and Workers, and for the Android client and Worker. Update requests, policy,
status, and package downloads use the authenticated Server origin. In the
personal deployment this origin is reachable through Tailscale. Devices never
follow a GitHub download redirect or need GitHub/AWS credentials.

This does not embed Tailscale, update the Server, add MCP operations, or update
iOS. Linux Workers remain managed by their deployment system. Development
checkouts are not self-updated; fixed slots and local configuration are unchanged.

## User controls

Open **Settings → Downloads → Application updates** to see installations owned
by the current user (the account owner can see all installations), their versions,
last contact, policy, and latest request. Requesting an update is idempotent while
that installation already has a pending request. Cancellation is available before
installation is handed to the OS. Automatic updates are disabled by default and
enabled separately for each installation.

Desktop clients also have **Application updates** in the tray menu. This window
uses HTTP authentication directly and is available even when the application
WebSocket protocol is incompatible. An authenticated startup failure opens it.
An Android startup failure similarly opens its native update activity; the Android
Worker has an Updates button on its main screen.

Workers install requested updates after accepted requests, managed commands and
monitors, and Visual output delivery have finished. An early `WORKING` response
is not completion: the command remains active through terminal-state persistence.
Preparation closes admission under the same locks used by new launches and startup
recovery, so neither can start between the idle check and handoff. Startup recovery
may be deferred while updating; an unavailable or incompatible WebSocket connection
alone does not block an otherwise idle Worker. Request results must also have been
persisted in the delivery journal. Once this boundary is
acquired, new commands receive `WORKER_UPDATING` without being executed. Pending
durable responses can be delivered after restart. A failed installer releases the
boundary. Long-running commands, monitors, and Visual handlers can delay an update indefinitely; the updater does
not kill them to meet a timer.

Desktop clients download updates in the background, then wait for **Install and
restart**. With automatic updates enabled they also install on ordinary application
exit, after normal UI-state saving. Android clients automatically install prepared
updates only while their UI is in the background; installation can still require
an OS confirmation. Mobile polling runs while the application process is alive;
Android background restrictions and a disconnected VPN can delay it.

## Protocol and persistence

`/api/updates/v1` is an independent HTTP protocol. Do not tie its schema to the
application or Worker WebSocket protocol number. Its additive evolution must
preserve old updater clients, including across major application releases.

* `POST /client/poll`: existing authenticated user session; registers or polls a
  client installation and reports progress.
* `POST /worker/poll`: existing Worker bearer credential; the Server derives the
  installation ID and owner from the authenticated Worker, never from the body.
* `GET /installations`: authorized inventory and the current release catalogue.
* `POST /installations/{id}/request`, `/cancel`, `/policy`: user-authorized actions.
* `POST /worker/request`, `/worker/policy`: an authenticated Worker acts on itself.
* `GET /{client|worker}/artifacts/{id}/{requestId}`: authorized, nonterminal request
  only; streams bytes from the Server without loading the package into memory.

The PostgreSQL `application_installations` table stores small JSON documents, not
packages. Row changes are serialized across Server processes with a transaction
and advisory lock. The latest request is retained for status and failure handling;
this is not a complete installation audit log. A client identity is local to its
Server/user pair; a Worker identity is derived from enrollment.

States are queued, downloading, ready, installing, waiting for Android confirmation,
and terminal success/failure/cancellation. Success is confirmed only when a poll
actually reports the requested application version. An installer exit code alone
does not establish success. A failed or cancelled version is not automatically
retried forever: an explicit request is required to retry the same version.

The catalogue selects packages from the exact deployed Server release. A release
that skips Android has no Android update offer. Existing requests retain their
original artifact, so keep their version directories until requests finish.
Downgrading Android is refused by its increasing version-code check.

## Installation mechanisms

Packages download to private application storage, with bounded size and SHA-256
verification before installation. Only same-origin HTTPS is accepted, with no
redirects; explicit HTTP loopback origins are allowed for isolated development.
Artifacts and catalogue are trusted release output, delivered by the authenticated
Server. This is not an independent release-signing trust system: a compromised
Server can authorize code execution on enrolled Workers, as it already can through
their normal command interface. Desktop release packages remain ad-hoc signed on
macOS and unsigned on Windows, matching the existing distribution policy.

* **macOS:** a separate, temporary launchd job unpacks and verifies the package,
  waits for the old process to exit, swaps directories, and starts the application.
  Worker updates keep the existing stable launcher app and LaunchAgent configuration,
  preserving the permission identity. Replacement errors restore the old directory.
  A durable transaction records replacement progress. The recovery job stays in
  `~/Library/LaunchAgents` until completion and restarts after an abnormal helper exit
  or the next login. It can restore the previous directory even if the normal app
  path is missing. After the new directory is committed, recovery launches that
  version instead. Recovery also recognizes a completed second directory move if
  termination happened before its phase marker was written; it keeps that new
  directory rather than replacing an app that may already be running. The previous directory is removed only after version confirmation.
* **Windows Worker:** an independent PowerShell process invokes the existing
  `update-service` installer from the downloaded distribution. It preserves the
  ProgramData configuration, LocalSystem account, service ACL, and recovery policy.
  The cache/installer inherit the protected service storage permissions. This reuses
  the service installer rather than maintaining a second MSI installer implementation.
* **Windows client:** the independent helper replaces the portable application in
  its existing writable directory and restarts it. User settings remain outside that
  directory. A protected Program Files installation requires a manual administrator
  update; the client does not silently elevate. Before changing directories, the
  helper registers a per-user Startup folder shortcut and a **Gromozeka update
  recovery** Start Menu shortcut pointing into the update cache. After an abrupt
  termination, use the shortcut or sign in again to recover; this does not promise
  immediate background recovery. The durable transaction restores the old directory
  or launches the committed new version. Both entry points are removed on completion.
* **Android:** `PackageInstaller` checks package name, release version, increasing
  version code, and the existing signing certificate. The updater handles
  `STATUS_PENDING_USER_ACTION`; it never claims ordinary apps can bypass Android
  confirmation. Grant installation permission once in Android settings. Allow
  notifications to receive confirmation prompts; the native update screen also
  offers confirmation and cancellation. Installation is performed by Android and
  preserves app data when the signing identity matches. The actual system confirmation
  intent is saved atomically in private storage and checked against the owned install
  session before reopening. The native update screen can therefore confirm after
  process death even when notifications are denied; it does not depend on a static
  in-memory intent. If Android has removed the session, retry the update explicitly.

Native helpers run outside the application process they replace. Local handoff,
process identity (PID plus birth time), Android session identity, and failure reports distinguish an active
installer from one that stopped without starting the new version. A missing network
connection is not used as an automatic rollback trigger. Startup after the native
handoff is confirmed by version polling; arbitrary application health failures are
not automatically rolled back. Inspect `installer.log` in the installation's update
cache if the helper fails. No credentials are passed on the helper command line.

## Release and deployment

The release workflow publishes the existing packages plus a macOS client app ZIP
and `catalog.json`. All catalogue entries and the catalogue itself are included in
`SHA256SUMS`. The deployment workflow verifies these hashes, creates a bounded flat
bundle, and uploads it to the existing private artifact bucket under
`runtime/application-updates/<version>.tar`.

Before starting the new Server, AWS deployment downloads and verifies that bundle
and atomically stages `<GROMOZEKA_HOME>/updates/<version>/`. The HTTP Server reads
only its own exact version's catalogue. A conflicting catalogue under an existing
version is rejected. The package cache is excluded from home backups because it is
reproducible release output; installation policy and requests are in the DB backup.
Retain old package directories while their requests remain active. No automatic
deletion of historic Server package directories is introduced in this change.

For another private deployment, use Python 3.9+ with the same release assets:

```sh
python3 deploy/aws/runtime/application-updates.py bundle VERSION RELEASE_ASSET_DIRECTORY updates.tar
python3 deploy/aws/runtime/application-updates.py stage VERSION updates.tar SERVER_HOME/updates
```

`GROMOZEKA_UPDATES_DIRECTORY` can select another mounted package directory. Keep it
outside any anonymously served static directory. Keep the Server behind the VPN;
the application endpoint itself authenticates requests but does not establish a VPN.

### Android signing setup

Before publishing Android builds, configure these GitHub Actions secrets using a
long-lived signing keystore, with an independently retained backup:

* `GROMOZEKA_ANDROID_KEYSTORE_BASE64`
* `GROMOZEKA_ANDROID_STORE_PASSWORD`
* `GROMOZEKA_ANDROID_KEY_ALIAS`
* `GROMOZEKA_ANDROID_KEY_PASSWORD`

The workflow fails before publication if any signing secret is absent. It removes
the temporary keystore after building. Verification-only runs can build unsigned
release APKs; those are not published. Local signing uses the same password/alias
variables and `GROMOZEKA_ANDROID_KEYSTORE` pointing to the keystore file. The version
code is `major * 1000000 + minor * 1000 + patch`, constrained to Android's integer
range. Changing the signing key is not an automatic migration.

The initial rollout requires one manual desktop/Worker update to install this
updater. Old debug-signed Android installations need a one-time reinstall and
enrollment unless their exact signing key is retained and deliberately reused.
Do not promise an in-place transition from unknown CI debug signing keys.

This PR does not provision signing secrets, publish a release, or update live devices.

## Recovery verification

`python3 scripts/test-worker-service.py` tests real macOS helper directory moves,
ordinary launch failure, and SIGKILL at replacement and rollback boundaries; only
OS launch and signing calls are stubbed. Windows CI runs
`scripts/test-windows-updates.ps1` against real PowerShell, Startup folder entries, and
recovery shortcuts with an isolated fixture executable. No installed Gromozeka
application or service is used by these recovery fixtures.

The opt-in `scripts/fixtures/android-update` application links the real updater and
native update screen. Its HTTP control-plane responses are replaced, and its
manifest removes silent-update permission to force the OS confirmation path. Build two
APKs with the same debug certificate and increasing versions, then run the harness
on an isolated API 33+ emulator (the script refuses physical devices):

```sh
./gradlew -PupdateAndroidFixture=true -PupdateFixtureVersion=1 :update-android-fixture:assembleDebug -q
cp scripts/fixtures/android-update/build/outputs/apk/debug/update-android-fixture-debug.apk /tmp/update-v1.apk
./gradlew -PupdateAndroidFixture=true -PupdateFixtureVersion=2 :update-android-fixture:assembleDebug -q
cp scripts/fixtures/android-update/build/outputs/apk/debug/update-android-fixture-debug.apk /tmp/update-v2.apk
python3 scripts/test-android-update-recovery.py --serial emulator-5554 /tmp/update-v1.apk /tmp/update-v2.apk
```

The harness exercises real PackageInstaller confirmation after process death with
notifications allowed and denied. It installs and removes only
`com.gromozeka.updatefixture`; it requires no Server, VPN, credentials, or physical
phone. This is not evidence about individual manufacturers' background policies.
