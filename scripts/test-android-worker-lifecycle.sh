#!/usr/bin/env bash
set -euo pipefail

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
worker_test_serial="${1:?Pass the explicit serial of a disposable API 35+ emulator}"
case "$worker_test_serial" in
  emulator-*) ;;
  *) echo 'Only a disposable emulator is allowed.' >&2; exit 1 ;;
esac
worker_project_root="$(cd "$(dirname "$0")/.." && pwd)"
worker_lifecycle_dir="$(mktemp -d "${TMPDIR:-/tmp}/gromozeka-worker-lifecycle.XXXXXX")"
mkdir -p "$worker_lifecycle_dir/resources/raw"
keytool -genkeypair -alias lifecycle -dname 'CN=Gromozeka Lifecycle Test' \
  -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "$worker_lifecycle_dir/tls.p12" \
  -storepass lifecycle-test -keypass lifecycle-test -validity 2 \
  -ext 'SAN=ip:10.0.2.2,ip:127.0.0.1' -ext 'BC=ca:true'
keytool -exportcert -rfc -alias lifecycle -keystore "$worker_lifecycle_dir/tls.p12" \
  -storepass lifecycle-test -file "$worker_lifecycle_dir/resources/raw/worker_lifecycle_ca.pem"

cd "$worker_project_root"
./gradlew -PworkerLifecycleResources="$worker_lifecycle_dir/resources" \
  :mobile-worker-android:assembleLifecycle :mobile-worker-android:assembleLifecycleAndroidTest -q
echo "Disposable TLS fixture: $worker_lifecycle_dir"
GROMOZEKA_ANDROID_LIFECYCLE_TEST=true \
ANDROID_LIFECYCLE_SERIAL="$worker_test_serial" \
ANDROID_LIFECYCLE_TLS_STORE="$worker_lifecycle_dir/tls.p12" \
ANDROID_LIFECYCLE_APK="$worker_project_root/mobile-worker-android/build/outputs/apk/lifecycle/mobile-worker-android-lifecycle.apk" \
ANDROID_LIFECYCLE_TEST_APK="$worker_project_root/mobile-worker-android/build/outputs/apk/androidTest/lifecycle/mobile-worker-android-lifecycle-androidTest.apk" \
./gradlew :server:test --tests 'com.gromozeka.server.AndroidWorkerLifecycleTest' -q
