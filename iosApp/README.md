# Gromozeka iOS

Native iOS shell for installing Gromozeka on a developer device.

Build for simulator from the command line:

```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Debug \
  -sdk iphonesimulator26.2 \
  ARCHS=arm64 \
  build
```

Build for a physical iPhone:

```bash
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -target iosApp \
  -configuration Debug \
  -sdk iphoneos26.2 \
  ARCHS=arm64 \
  GROMOZEKA_REMOTE_URL='wss://<server-host>/ws' \
  -allowProvisioningUpdates \
  build
```

`GROMOZEKA_REMOTE_URL` is embedded in `Info.plist` as a first-run fallback.
Without it, the app asks for the Server address. Once connected, the selected
address is stored on the device and takes precedence over the bundled fallback.

Install the freshly built physical-device app:

```bash
xcrun devicectl device install app \
  --device <device-uuid> \
  iosApp/build/Debug-iphoneos/Gromozeka.app
```

Important: use `iosApp/build/Debug-iphoneos/Gromozeka.app` after command-line
builds. Do not install a `DerivedData/.../Gromozeka.app` unless you have just
verified its embedded `GromozekaPresentation.framework` timestamp; DerivedData
can keep stale Kotlin frameworks and make the phone look like it is running old
UI code.

Install/run from Xcode after signing is configured:

1. Open `iosApp/iosApp.xcodeproj`.
2. Select the `iosApp` scheme.
3. Select the connected iPhone.
4. Press Run.

The iOS shell hosts the shared Compose Multiplatform client and uses the same
typed remote protocol as the desktop, Android, and web clients.
