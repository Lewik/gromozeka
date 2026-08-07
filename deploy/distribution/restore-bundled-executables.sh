#!/usr/bin/env bash
set -euo pipefail

source_root="${1:?source root is required}"
applications_root="${2:?applications root is required}"
application_roots=()

while IFS= read -r application; do
  application_roots+=("$application/Contents/app/resources/local-worker")
done < <(find "$applications_root" -type d -name Gromozeka.app -print)

[[ "${#application_roots[@]}" -gt 0 ]] || {
  echo "Packaged Gromozeka.app was not found under $applications_root" >&2
  exit 2
}

while IFS= read -r source; do
  relative_path="${source#"$source_root"/}"
  for application_root in "${application_roots[@]}"; do
    packaged="$application_root/$relative_path"
    [[ -f "$packaged" ]] || {
      echo "Packaged Local Worker file is missing: $packaged" >&2
      exit 2
    }
    chmod a+x "$packaged"
  done
done < <(find "$source_root" -type f -perm -111 -print)
