#!/bin/bash
set -euo pipefail
archive="$1"
destination="$2"
component="$3"
request_id="$4"
old_pid="$5"
result="$6"
job_label="$7"
user_id="$8"
printf '%s\n' "$$" > "$(dirname "$result")/installer-${request_id}.pid"
staging="${destination}.update-${request_id}"
previous="${destination}.previous-${request_id}"
swapped=false
worker_stopped=false
service="gui/${user_id}/com.gromozeka.worker"
service_plist="$HOME/Library/LaunchAgents/com.gromozeka.worker.plist"

finish() {
  code=$?
  trap - EXIT
  if [[ "$code" != 0 ]]; then
    if [[ "$swapped" == true && -d "$previous" ]]; then
      rm -rf -- "$destination"
      mv -- "$previous" "$destination"
    fi
    if [[ "$worker_stopped" == true ]]; then
      /bin/launchctl bootstrap "gui/$user_id" "$service_plist" || true
    elif [[ "$component" == CLIENT && -d "$destination" ]]; then
      /usr/bin/open "$destination" || true
    fi
    printf '{"requestId":"%s","phase":"FAILED","message":"Installation failed; see installer.log"}\n' "$request_id" > "$result.new"
    mv "$result.new" "$result"
  fi
  rm -rf -- "$staging"
  /bin/launchctl remove "$job_label" || true
}
trap finish EXIT

[[ "$request_id" =~ ^[a-zA-Z0-9-]+$ && "$old_pid" =~ ^[0-9]+$ && -d "$destination" && -f "$archive" ]]
[[ ! -e "$staging" && ! -e "$previous" ]]
mkdir "$staging"
if [[ "$component" == CLIENT ]]; then
  [[ "$destination" == *.app ]]
  /usr/bin/ditto -x -k "$archive" "$staging"
  source="$staging/Gromozeka.app"
  [[ -x "$source/Contents/MacOS/Gromozeka" ]]
  /usr/bin/codesign --verify --deep "$source"
else
  [[ "$component" == WORKER && -f "$service_plist" && -f "$destination/app/gromozeka-worker.jar" ]]
  /usr/bin/tar -xzf "$archive" --strip-components=1 -C "$staging"
  source="$staging"
  [[ -x "$source/bin/gromozeka-worker" && -f "$source/app/gromozeka-worker.jar" ]]
  java="$source/runtime/java/bin/java"
  [[ -x "$java" ]] || java="$source/runtime/java/Contents/Home/bin/java"
  "$java" -version
  /bin/launchctl bootout "$service"
  worker_stopped=true
fi

for attempt in {1..120}; do
  kill -0 "$old_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$old_pid" 2>/dev/null; then
  echo "Application did not exit; refusing to replace running files" >&2
  exit 1
fi
mv -- "$destination" "$previous"
swapped=true
mv -- "$source" "$destination"
if [[ "$component" == CLIENT ]]; then
  /usr/bin/open "$destination"
else
  /bin/launchctl bootstrap "gui/$user_id" "$service_plist"
  /bin/launchctl print "$service"
fi
