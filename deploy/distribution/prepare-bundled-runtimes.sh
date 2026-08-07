#!/usr/bin/env bash
set -euo pipefail

component="${1:?component is required}"
platform="${2:?platform is required}"
architecture="${3:?architecture is required}"
destination="${4:?destination is required}"
repository_root="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
manifest="$repository_root/deploy/distribution/bundled-runtime-versions.properties"
cache_root="${GROMOZEKA_BUILD_RUNTIME_CACHE:-$repository_root/build/bundled-runtime-cache}"

case "$component" in
  server) runtime_kinds=(java) ;;
  worker) runtime_kinds=(java node) ;;
  *)
    echo "Unsupported runtime component: $component" >&2
    exit 2
    ;;
esac

case "$platform/$architecture" in
  macos/arm64) platform_key="MACOS_ARM64" ;;
  linux/x64) platform_key="LINUX_X64" ;;
  windows/x64) platform_key="WINDOWS_X64" ;;
  *)
    echo "Unsupported bundled runtime target: $platform/$architecture" >&2
    exit 2
    ;;
esac

set -a
source "$manifest"
set +a

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    echo "sha256sum or shasum is required to verify bundled runtimes." >&2
    return 2
  fi
  [[ "$actual" == "$expected" ]] || {
    echo "Bundled runtime checksum mismatch: expected $expected, got $actual" >&2
    return 2
  }
}

runtime_executable() {
  local kind="$1"
  case "$kind/$platform_key" in
    java/MACOS_ARM64) printf 'Contents/Home/bin/java\n' ;;
    java/LINUX_X64) printf 'bin/java\n' ;;
    java/WINDOWS_X64) printf 'bin/java.exe\n' ;;
    node/MACOS_ARM64 | node/LINUX_X64) printf 'bin/node\n' ;;
    node/WINDOWS_X64) printf 'node.exe\n' ;;
  esac
}

verify_runtime_contents() {
  local kind="$1"
  local root="$2"
  local executable
  executable="$(runtime_executable "$kind")"
  [[ -f "$root/$executable" ]] || {
    echo "Bundled $kind runtime does not contain $executable" >&2
    return 2
  }
  if [[ "$kind" == "java" ]]; then
    local legal_directory="$root/legal"
    [[ "$platform_key" == "MACOS_ARM64" ]] && legal_directory="$root/Contents/Home/legal"
    [[ -d "$legal_directory" ]] || {
      echo "Bundled Java runtime does not contain its legal notices." >&2
      return 2
    }
  else
    [[ -f "$root/LICENSE" ]] || {
      echo "Bundled Node.js runtime does not contain LICENSE." >&2
      return 2
    }
  fi
}

minimize_node_runtime() {
  local root="$1"
  local minimal="$2"
  mkdir -p "$minimal"
  cp -p "$root/LICENSE" "$minimal/LICENSE"
  if [[ "$platform_key" == "WINDOWS_X64" ]]; then
    cp -p "$root/node.exe" "$minimal/node.exe"
  else
    mkdir -p "$minimal/bin"
    cp -p "$root/bin/node" "$minimal/bin/node"
  fi
}

prepare_runtime() {
  local kind="$1"
  local uppercase_kind
  case "$kind" in
    java) uppercase_kind="JAVA" ;;
    node) uppercase_kind="NODE" ;;
  esac
  local version_variable="GROMOZEKA_${uppercase_kind}_VERSION"
  local url_variable="GROMOZEKA_${uppercase_kind}_${platform_key}_URL"
  local sha_variable="GROMOZEKA_${uppercase_kind}_${platform_key}_SHA256"
  local version="${!version_variable}"
  local url="${!url_variable}"
  local sha256="${!sha_variable}"
  local cache="$cache_root/v2/$kind/$platform-$architecture/${version//[^A-Za-z0-9._-]/_}-${sha256:0:16}"
  local complete="$cache/.complete"

  if [[ ! -f "$complete" ]]; then
    local temporary archive extracted unpacked source_root minimal
    temporary="$(mktemp -d "$cache_root/.${kind}.XXXXXX")"
    archive="$temporary/runtime.archive"
    extracted="$temporary/extracted"
    unpacked="$temporary/unpacked"
    trap 'rm -rf "$temporary"' EXIT
    mkdir -p "$extracted" "$unpacked"
    echo "Bundling $kind $version for $platform-$architecture..." >&2
    curl --fail --location --silent --show-error "$url" --output "$archive"
    verify_sha256 "$archive" "$sha256"
    if [[ "$url" == *.zip ]]; then
      unzip -q "$archive" -d "$unpacked"
      source_root="$(find "$unpacked" -mindepth 1 -maxdepth 1 -type d -print -quit)"
      [[ -n "$source_root" ]] || {
        echo "Bundled runtime archive has no root directory: $url" >&2
        return 2
      }
      cp -R -p "$source_root"/. "$extracted"/
    else
      tar -xzf "$archive" -C "$extracted" --strip-components=1
    fi
    verify_runtime_contents "$kind" "$extracted"
    if [[ "$kind" == "node" ]]; then
      minimal="$temporary/minimal"
      minimize_node_runtime "$extracted" "$minimal"
      rm -rf "$extracted"
      mv "$minimal" "$extracted"
    fi
    mkdir -p "$(dirname "$cache")"
    rm -rf "$cache"
    touch "$extracted/.complete"
    mv "$extracted" "$cache"
    rm -rf "$temporary"
    trap - EXIT
  fi

  verify_runtime_contents "$kind" "$cache"
  mkdir -p "$destination/$kind"
  cp -R -p "$cache"/. "$destination/$kind"/
  rm -f "$destination/$kind/.complete"
  chmod -R u+w "$destination/$kind"
}

rm -rf "$destination"
mkdir -p "$destination" "$cache_root"
for runtime_kind in "${runtime_kinds[@]}"; do
  prepare_runtime "$runtime_kind"
done
