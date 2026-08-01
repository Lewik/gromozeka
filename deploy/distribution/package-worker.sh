#!/usr/bin/env bash
set -euo pipefail

platform="${1:?platform is required}"
architecture="${2:?architecture is required}"
output_directory="${3:?output directory is required}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

case "$platform/$architecture" in
  macos/arm64)
    expected_system="Darwin"
    expected_machine="arm64"
    ;;
  linux/x64)
    expected_system="Linux"
    expected_machine="x86_64"
    ;;
  *)
    echo "Unsupported Worker target: $platform/$architecture" >&2
    exit 2
    ;;
esac

if [[ "$(uname -s)" != "$expected_system" || "$(uname -m)" != "$expected_machine" ]]; then
  echo "Worker target $platform/$architecture does not match this host: $(uname -s)/$(uname -m)" >&2
  exit 2
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  java_home="$JAVA_HOME"
elif [[ -x /usr/libexec/java_home ]]; then
  java_home="$(/usr/libexec/java_home -v 21)"
else
  java_binary="$(readlink -f "$(command -v java)")"
  java_home="$(cd "$(dirname "$java_binary")/.." && pwd)"
fi

package_name="gromozeka-worker-${platform}-${architecture}"
staging_root="$(mktemp -d)"
package_root="$staging_root/$package_name"
runtime_modules="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,jdk.charsets,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.httpserver,jdk.jfr,jdk.localedata,jdk.management,jdk.naming.dns,jdk.unsupported,jdk.zipfs"

cleanup() {
  rm -rf "$staging_root"
}
trap cleanup EXIT

mkdir -p "$package_root/app" "$package_root/bin" "$package_root/config" "$output_directory"

"$java_home/bin/jlink" \
  --add-modules "$runtime_modules" \
  --bind-services \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress=zip-6 \
  --output "$package_root/runtime"

cp "$repository_root/worker/build/libs/gromozeka-worker.jar" "$package_root/app/"
cp "$repository_root/deploy/distribution/worker.yaml.example" "$package_root/config/"
cp "$repository_root/deploy/distribution/WORKER_README.md" "$package_root/README.md"
cp "$repository_root/deploy/distribution/gromozeka-worker" "$package_root/bin/"
cp "$repository_root/LICENSE" "$package_root/LICENSE"
chmod +x "$package_root/bin/gromozeka-worker"

tar -C "$staging_root" -czf "$output_directory/$package_name.tar.gz" "$package_name"
