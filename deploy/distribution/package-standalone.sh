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
  macos/arm64 | linux/x64 | windows/x64) ;;
  *)
    echo "Unsupported standalone target: $platform/$architecture" >&2
    exit 2
    ;;
esac

package_name="gromozeka-${component}-${platform}-${architecture}"
staging_root="$(mktemp -d)"
package_root="$staging_root/$package_name"

cleanup() {
  rm -rf "$staging_root"
}
trap cleanup EXIT

mkdir -p "$package_root/app" "$package_root/bin" "$package_root/config" "$output_directory"
output_directory="$(cd "$output_directory" && pwd)"
cp "$repository_root/$component/build/libs/gromozeka-$component.jar" "$package_root/app/"
cp "$repository_root/deploy/distribution/runtime-versions.properties" "$package_root/bin/"
cp "$repository_root/LICENSE" "$package_root/LICENSE"
cp "$repository_root/THIRD_PARTY_NOTICES.md" "$package_root/THIRD_PARTY_NOTICES.md"

if [[ "$platform" == "windows" ]]; then
  cp "$repository_root/deploy/distribution/runtime-bootstrap.ps1" "$package_root/bin/"
  cp "$repository_root/deploy/distribution/gromozeka-$component.cmd" "$package_root/bin/"
  cp "$repository_root/deploy/distribution/gromozeka-$component.ps1" "$package_root/bin/"
else
  cp "$repository_root/deploy/distribution/runtime-bootstrap.sh" "$package_root/bin/"
  cp "$repository_root/deploy/distribution/gromozeka-$component" "$package_root/bin/"
  chmod +x "$package_root/bin/gromozeka-$component" "$package_root/bin/runtime-bootstrap.sh"
fi

if [[ "$component" == "server" ]]; then
  web_source="$repository_root/presentation/build/dist/wasmJs/productionExecutable"
  [[ -f "$web_source/index.html" ]] || {
    echo "Production Web client was not built: $web_source" >&2
    exit 2
  }
  cp "$repository_root/deploy/distribution/server.yaml.example" "$package_root/config/"
  cp "$repository_root/deploy/distribution/STANDALONE_SERVER_README.md" "$package_root/README.md"
  mkdir -p "$package_root/web"
  cp -R "$web_source"/. "$package_root/web/"
  find "$package_root/web" -type f \( -name '*.wasm.br' -o -name '*.wasm.gz' -o -name 'gromozeka.js.br' -o -name 'gromozeka.js.gz' \) -delete
  while IFS= read -r -d '' asset; do
    gzip -n -9 -c "$asset" > "$asset.gz"
    if command -v brotli >/dev/null 2>&1; then
      brotli --force --quality=11 --no-copy-stat "$asset"
    fi
  done < <(find "$package_root/web" -type f \( -name '*.wasm' -o -name 'gromozeka.js' \) -print0)
else
  npm --prefix "$repository_root/browser-mcp" run verify --silent
  cp "$repository_root/deploy/distribution/worker.yaml.example" "$package_root/config/"
  cp "$repository_root/deploy/distribution/WORKER_README.md" "$package_root/README.md"
  mkdir -p "$package_root/app/browser-mcp"
  cp "$repository_root/browser-mcp/package.json" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/package-lock.json" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/README.md" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/NOTICE" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/UPSTREAM.md" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/THIRD_PARTY_NOTICES.txt" "$package_root/app/browser-mcp/"
  cp "$repository_root/browser-mcp/LICENSE" "$package_root/app/browser-mcp/"
  cp -R "$repository_root/browser-mcp/node_modules" "$package_root/app/browser-mcp/"
  rm -rf "$package_root/app/browser-mcp/node_modules/.bin"

  if [[ "$platform" == "windows" ]]; then
    cp "$repository_root/deploy/distribution/gromozeka-browser-mcp.cmd" "$package_root/bin/"
    cp "$repository_root/deploy/distribution/gromozeka-browser-mcp.ps1" "$package_root/bin/"
  else
    cp "$repository_root/deploy/distribution/gromozeka-browser-mcp" "$package_root/bin/"
    chmod +x "$package_root/bin/gromozeka-browser-mcp"
  fi

  if [[ "$platform/$architecture" == "macos/arm64" ]]; then
    native_launcher="$repository_root/build/native-launchers/macos-arm64/gromozeka-worker-launcher"
    if [[ "$(uname -s)/$(uname -m)" == "Darwin/arm64" ]]; then
      mkdir -p "$(dirname "$native_launcher")"
      cc -Os -arch arm64 -mmacosx-version-min=12.0 \
        "$repository_root/deploy/distribution/macos-worker-launcher.c" \
        -framework CoreGraphics \
        -framework ApplicationServices \
        -o "$native_launcher"
      strip "$native_launcher"
    fi
    [[ -x "$native_launcher" ]] || {
      echo "The macOS ARM64 Worker launcher was not built: $native_launcher" >&2
      exit 2
    }
    mkdir -p "$package_root/app/native"
    cp "$native_launcher" "$package_root/app/native/gromozeka-worker-launcher"
    cp "$repository_root/deploy/distribution/gromozeka-worker-service" "$package_root/bin/"
    chmod +x \
      "$package_root/app/native/gromozeka-worker-launcher" \
      "$package_root/bin/gromozeka-worker-service"
  fi
fi

if [[ "$platform" == "windows" ]]; then
  (
    cd "$staging_root"
    zip -q -r "$output_directory/$package_name.zip" "$package_name"
  )
else
  tar -C "$staging_root" -czf "$output_directory/$package_name.tar.gz" "$package_name"
fi
