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
[[ "$request_id" =~ ^[a-zA-Z0-9-]{1,80}$ && "$old_pid" =~ ^[0-9]+$ && "$user_id" =~ ^[0-9]+$ && "$job_label" =~ ^[a-zA-Z0-9.-]+$ ]]
cache="$(dirname "$result")"
started="$(LC_ALL=C TZ=UTC /bin/ps -p "$$" -o lstart=)"
started="$(LC_ALL=C TZ=UTC /bin/date -j -f '%a %b %e %T %Y' "$started" '+%s')"
printf '%s %s\n' "$$" "$started" > "$cache/installer-${request_id}.process.new"
mv "$cache/installer-${request_id}.process.new" "$cache/installer-${request_id}.process"
staging="${destination}.update-${request_id}"
previous="${destination}.previous-${request_id}"
transaction="$cache/transaction-${request_id}"
service="gui/${user_id}/com.gromozeka.worker"
service_plist="$HOME/Library/LaunchAgents/com.gromozeka.worker.plist"
recovery_plist="$HOME/Library/LaunchAgents/${job_label}.plist"

write_phase() {
  printf '%s\n' "$1" > "$transaction.new" || return 1
  mv "$transaction.new" "$transaction" || return 1
  /bin/sync
}

start_installed() {
  if [[ "$component" == CLIENT ]]; then
    /usr/bin/open "$destination"
  else
    /bin/launchctl print "$service" >/dev/null 2>&1 || /bin/launchctl bootstrap "gui/$user_id" "$service_plist"
    /bin/launchctl print "$service"
  fi
}

restore_previous() {
  write_phase rolling-back || return 1
  if [[ -d "$previous" ]]; then
    rm -rf -- "$destination" || return 1
    mv -- "$previous" "$destination" || return 1
    /bin/sync || return 1
  fi
  [[ -d "$destination" ]] || return 1
  printf '{"requestId":"%s","phase":"FAILED","message":"Interrupted or failed installation restored; see installer.log"}\n' "$request_id" > "$result.new" || return 1
  mv "$result.new" "$result" || return 1
  write_phase failed || return 1
  start_installed
}

finish() {
  code=$?
  trap - EXIT
  if [[ "$code" != 0 ]]; then
    restore_previous || exit 1
  fi
  rm -rf -- "$staging"
  rm -f -- "$recovery_plist"
  /bin/sync
  /bin/launchctl remove "$job_label" || true
}
trap finish EXIT

if [[ -f "$transaction" ]]; then
  phase="$(cat "$transaction")"
  case "$phase" in
    installed) start_installed; write_phase complete ;;
    complete) ;;
    prepared|stopping|replacing|rolling-back|failed) restore_previous ;;
    *) echo 'Unknown installation transaction state' >&2; exit 1 ;;
  esac
  exit 0
fi

[[ -d "$destination" && -f "$archive" && ! -e "$staging" && ! -e "$previous" ]]
write_phase prepared
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
  write_phase stopping
  /bin/launchctl bootout "$service"
fi

for attempt in {1..120}; do
  kill -0 "$old_pid" 2>/dev/null || break
  sleep 1
done
if kill -0 "$old_pid" 2>/dev/null; then
  echo 'Application did not exit; refusing to replace running files' >&2
  exit 1
fi
write_phase replacing
mv -- "$destination" "$previous"
mv -- "$source" "$destination"
write_phase installed
start_installed
write_phase complete
