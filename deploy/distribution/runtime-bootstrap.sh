#!/usr/bin/env bash
set -euo pipefail

_gromozeka_runtime_script_dir="$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
_gromozeka_runtime_manifest="${GROMOZEKA_RUNTIME_MANIFEST:-$_gromozeka_runtime_script_dir/runtime-versions.properties}"

[[ -f "$_gromozeka_runtime_manifest" ]] || {
  echo "Gromozeka runtime manifest was not found: $_gromozeka_runtime_manifest" >&2
  exit 2
}

set -a
source "$_gromozeka_runtime_manifest"
set +a

gromozeka_platform_key() {
  case "$(uname -s)/$(uname -m)" in
    Darwin/arm64) printf 'MACOS_ARM64\n' ;;
    Linux/x86_64) printf 'LINUX_X64\n' ;;
    *)
      echo "Unsupported Gromozeka standalone platform: $(uname -s)/$(uname -m)" >&2
      return 2
      ;;
  esac
}

gromozeka_runtime_cache() {
  printf '%s\n' "${GROMOZEKA_RUNTIME_CACHE:-${GROMOZEKA_HOME:-$HOME/.gromozeka}/runtimes}"
}

gromozeka_verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$file" | awk '{print $1}')"
  else
    echo "Install sha256sum or shasum to verify managed runtimes." >&2
    return 2
  fi
  [[ "$actual" == "$expected" ]] || {
    echo "Runtime archive checksum mismatch: expected $expected, got $actual" >&2
    return 2
  }
}

gromozeka_install_tar_runtime() {
  local kind="$1"
  local version="$2"
  local url="$3"
  local sha256="$4"
  local executable="$5"
  local platform="$6"
  local cache target parent lock temporary archive extracted platform_path
  cache="$(gromozeka_runtime_cache)"
  platform_path="$(printf '%s' "$platform" | tr '[:upper:]' '[:lower:]')"
  target="$cache/$kind/$version/$platform_path"
  parent="$(dirname "$target")"
  lock="$target.lock"

  if [[ -x "$target/$executable" && -f "$target/.complete" ]]; then
    printf '%s\n' "$target/$executable"
    return
  fi

  mkdir -p "$parent"
  local wait_count=0
  until mkdir "$lock" 2>/dev/null; do
    if [[ -x "$target/$executable" && -f "$target/.complete" ]]; then
      printf '%s\n' "$target/$executable"
      return
    fi
    wait_count=$((wait_count + 1))
    if [[ "$wait_count" -ge 120 ]]; then
      echo "Timed out waiting for runtime installation lock: $lock" >&2
      return 2
    fi
    sleep 1
  done

  (
    temporary="$(mktemp -d "$parent/.${kind}-download.XXXXXX")"
    archive="$temporary/runtime.tar.gz"
    extracted="$temporary/extracted"
    trap 'rm -rf "$temporary" "$lock"' EXIT

    rm -rf "$target"
    mkdir -p "$extracted"
    echo "Downloading $kind runtime $version for $platform_path..." >&2
    command -v curl >/dev/null 2>&1 || {
      echo "Install curl to download the managed Gromozeka runtime." >&2
      return 2
    }
    curl --fail --location --silent --show-error "$url" --output "$archive"
    gromozeka_verify_sha256 "$archive" "$sha256"
    tar -xzf "$archive" -C "$extracted" --strip-components=1
    [[ -x "$extracted/$executable" ]] || {
      echo "Downloaded $kind runtime does not contain $executable" >&2
      return 2
    }
    touch "$extracted/.complete"
    mv "$extracted" "$target"
    printf '%s\n' "$target/$executable"
  )
}

gromozeka_java_compatible() {
  local candidate="$1"
  [[ -x "$candidate" ]] || return 1
  local output major
  output="$("$candidate" -version 2>&1)" || return 1
  major="$(printf '%s\n' "$output" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  [[ -n "$major" && "$major" -ge 21 ]]
}

gromozeka_node_compatible() {
  local candidate="$1"
  [[ -x "$candidate" ]] || return 1
  local version major
  version="$("$candidate" --version 2>/dev/null)" || return 1
  major="${version#v}"
  major="${major%%.*}"
  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 20 ]]
}

gromozeka_resolve_java() {
  if [[ -n "${GROMOZEKA_JAVA_EXECUTABLE:-}" ]]; then
    gromozeka_java_compatible "$GROMOZEKA_JAVA_EXECUTABLE" || {
      echo "GROMOZEKA_JAVA_EXECUTABLE must point to Java 21 or newer." >&2
      return 2
    }
    printf '%s\n' "$GROMOZEKA_JAVA_EXECUTABLE"
    return
  fi

  local candidate
  for candidate in \
    "${GROMOZEKA_JAVA_HOME:+$GROMOZEKA_JAVA_HOME/bin/java}" \
    "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
    "$(command -v java 2>/dev/null || true)"; do
    [[ -n "$candidate" ]] || continue
    if gromozeka_java_compatible "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  local platform url sha executable
  platform="$(gromozeka_platform_key)"
  case "$platform" in
    MACOS_ARM64)
      url="$GROMOZEKA_JAVA_MACOS_ARM64_URL"
      sha="$GROMOZEKA_JAVA_MACOS_ARM64_SHA256"
      executable="Contents/Home/bin/java"
      ;;
    LINUX_X64)
      url="$GROMOZEKA_JAVA_LINUX_X64_URL"
      sha="$GROMOZEKA_JAVA_LINUX_X64_SHA256"
      executable="bin/java"
      ;;
  esac
  gromozeka_install_tar_runtime java "$GROMOZEKA_JAVA_CACHE_VERSION" "$url" "$sha" "$executable" "$platform"
}

gromozeka_resolve_node() {
  if [[ -n "${GROMOZEKA_NODE_EXECUTABLE:-}" ]]; then
    gromozeka_node_compatible "$GROMOZEKA_NODE_EXECUTABLE" || {
      echo "GROMOZEKA_NODE_EXECUTABLE must point to Node.js 20 or newer." >&2
      return 2
    }
    printf '%s\n' "$GROMOZEKA_NODE_EXECUTABLE"
    return
  fi

  local candidate
  for candidate in \
    "${GROMOZEKA_NODE_HOME:+$GROMOZEKA_NODE_HOME/bin/node}" \
    "$(command -v node 2>/dev/null || true)"; do
    [[ -n "$candidate" ]] || continue
    if gromozeka_node_compatible "$candidate"; then
      printf '%s\n' "$candidate"
      return
    fi
  done

  local platform url sha
  platform="$(gromozeka_platform_key)"
  case "$platform" in
    MACOS_ARM64)
      url="$GROMOZEKA_NODE_MACOS_ARM64_URL"
      sha="$GROMOZEKA_NODE_MACOS_ARM64_SHA256"
      ;;
    LINUX_X64)
      url="$GROMOZEKA_NODE_LINUX_X64_URL"
      sha="$GROMOZEKA_NODE_LINUX_X64_SHA256"
      ;;
  esac
  gromozeka_install_tar_runtime node "$GROMOZEKA_NODE_CACHE_VERSION" "$url" "$sha" bin/node "$platform"
}
