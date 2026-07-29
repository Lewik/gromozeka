#!/usr/bin/env bash
set -euo pipefail

component="${1:?component is required}"
platform="${2:?platform is required}"
architecture="${3:?architecture is required}"
output_directory="${4:?output directory is required}"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

case "$component" in
  server | worker) ;;
  *)
    echo "Unsupported component: $component" >&2
    exit 2
    ;;
esac

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
    echo "Unsupported $component target: $platform/$architecture" >&2
    exit 2
    ;;
esac

if [[ "$(uname -s)" != "$expected_system" || "$(uname -m)" != "$expected_machine" ]]; then
  echo "$component target $platform/$architecture does not match this host: $(uname -s)/$(uname -m)" >&2
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

package_name="gromozeka-${component}-${platform}-${architecture}"
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

cp "$repository_root/$component/build/libs/gromozeka-$component.jar" "$package_root/app/"
cp "$repository_root/LICENSE" "$package_root/LICENSE"

if [[ "$component" == "server" ]]; then
  web_source="$repository_root/presentation/build/dist/wasmJs/productionExecutable"
  [[ -f "$web_source/index.html" ]] || {
    echo "Production Web client was not built: $web_source" >&2
    exit 2
  }

  cp "$repository_root/deploy/distribution/server.yaml.example" "$package_root/config/"
  cp "$repository_root/deploy/distribution/SERVER_README.md" "$package_root/README.md"
  cp "$repository_root/deploy/distribution/gromozeka-server" "$package_root/bin/"
  chmod +x "$package_root/bin/gromozeka-server"
  cp -R "$web_source" "$package_root/web"

  while IFS= read -r -d '' asset; do
    gzip -n -9 -c "$asset" > "$asset.gz"
  done < <(find "$package_root/web" -type f \( -name '*.wasm' -o -name 'gromozeka.js' \) -print0)
else
  cp "$repository_root/deploy/distribution/worker.yaml.example" "$package_root/config/"
  cp "$repository_root/deploy/distribution/WORKER_README.md" "$package_root/README.md"
  cp "$repository_root/deploy/distribution/gromozeka-worker" "$package_root/bin/"
  chmod +x "$package_root/bin/gromozeka-worker"
fi

tar -C "$staging_root" -czf "$output_directory/$package_name.tar.gz" "$package_name"
