# Native Libraries for JNativeHook

This folder contains native libraries for JNativeHook to work in packaged macOS applications.

## Automatic Extraction

The native libraries are **automatically extracted** during the build process via the `extractNativeLibraries` Gradle task.

### Generated Files

When you run `./gradlew packageDmg` or `./gradlew packageDistributionForCurrentOS`, the following files are automatically created:

1. `libJNativeHook-x86_64.dylib` - Native library for Intel Macs
2. `libJNativeHook-arm64.dylib` - Native library for Apple Silicon Macs

### Manual Extraction (if needed)

If you need to extract the libraries manually:

```bash
./gradlew extractNativeLibraries
```

## Why This Is Needed

JNativeHook extracts native libraries to temporary directories during runtime, which doesn't work in packaged macOS applications due to permission restrictions. By bundling the libraries with the app and configuring library paths, we ensure smooth operation.

## How It Works

1. **Build-time**: Gradle task extracts `.dylib` files from jnativehook JAR
2. **Package-time**: Files are included in the .app bundle via `appResourcesRootDir`
3. **Runtime**: `MacOSGlobalHotkeyController` configures JNativeHook to use bundled libraries