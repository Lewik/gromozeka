# Gromozeka iOS

Native iOS shell for installing Gromozeka on a developer device.

Build for simulator from the command line:

```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Debug \
  -sdk iphonesimulator26.2 \
  build
```

Build for a physical iPhone:

```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Debug \
  -sdk iphoneos26.2 \
  -allowProvisioningUpdates \
  build
```

Install/run from Xcode after signing is configured:

1. Open `iosApp/iosApp.xcodeproj`.
2. Select the `iosApp` scheme.
3. Select the connected iPhone.
4. Press Run.

The first version is intentionally native SwiftUI and probes the existing remote server over JSON WebSocket.
Full typed protocol sharing with Kotlin Multiplatform can be added after the install loop is stable.

The default endpoint is the same secure tailnet endpoint used by the JVM client:

```text
wss://macbook-pro.tail05115b.ts.net/ws
```
