#!/bin/bash
# Shared one-run-at-a-time lock for WSL scripts (plan 2.2). Source it; needs PYJVM_LOCK (set in ~/pyjvm-env.sh) to name
# the Windows-side pyjvm-work\run.lock as seen from WSL, e.g. /mnt/e/pyjvm-work/run.lock.
#   take_lock "<who> <what>"   waits while another run holds the lock; installs an EXIT trap that releases it
#   drop_lock                  removes the lock only if it is still ours, and clears the trap
: "${PYJVM_LOCK:?PYJVM_LOCK is not set (add it to ~/pyjvm-env.sh)}"
if [ ! -d "$(dirname "$PYJVM_LOCK")" ]; then
  echo "run lock directory $(dirname "$PYJVM_LOCK") does not exist" >&2
  exit 2
fi
_LOCK_TEXT=""

take_lock() {
  local text="$1 $$ $(date)"
  trap drop_lock EXIT
  trap 'drop_lock; exit 130' INT TERM
  until ( set -o noclobber; echo "$text" > "$PYJVM_LOCK" ) 2>/dev/null; do
    [ -e "$PYJVM_LOCK" ] || { echo "cannot create $PYJVM_LOCK" >&2; exit 2; }
    sleep 30
  done
  _LOCK_TEXT="$text"
}

drop_lock() {
  if [ -n "$_LOCK_TEXT" ] && [ "$(cat "$PYJVM_LOCK" 2>/dev/null)" = "$_LOCK_TEXT" ]; then rm -f "$PYJVM_LOCK"; fi
  _LOCK_TEXT=""
  trap - EXIT INT TERM
}
