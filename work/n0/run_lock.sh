#!/bin/bash
# Shared run lock for WSL scripts (plan 2.2), with up to PYJVM_LOCK_SLOTS (default 3) concurrent JVM runs. Source it;
# needs PYJVM_LOCK (set in ~/pyjvm-env.sh) to name the Windows-side pyjvm-work\run.lock as seen from WSL, e.g.
# /mnt/e/pyjvm-work/run.lock. Slot files: $PYJVM_LOCK (slot 1, the historical single lock), $PYJVM_LOCK.2, $PYJVM_LOCK.3.
#   take_lock "<who> <what>"   waits until a slot is free; installs an EXIT trap that releases it
#                              with PYJVM_LOCK_EXCLUSIVE=1: takes every slot (in order), so the run is alone (full gates)
#   drop_lock                  removes the slot files that are still ours, and clears the trap
# Stale slot: a slot file whose timestamp predates WSL's boot (`uptime -s`) belongs to a process that no longer exists.
: "${PYJVM_LOCK:?PYJVM_LOCK is not set (add it to ~/pyjvm-env.sh)}"
if [ ! -d "$(dirname "$PYJVM_LOCK")" ]; then
  echo "run lock directory $(dirname "$PYJVM_LOCK") does not exist" >&2
  exit 2
fi
_LOCK_TEXT=""
_LOCK_HELD=()

_lock_slot_file() { if [ "$1" = 1 ]; then echo "$PYJVM_LOCK"; else echo "$PYJVM_LOCK.$1"; fi; }

_lock_try() {   # _lock_try FILE TEXT -> 0 if created
  ( set -o noclobber; echo "$2" > "$1" ) 2>/dev/null
}

take_lock() {
  local text="$1 $$ $(date)" n="${PYJVM_LOCK_SLOTS:-3}" i f
  trap drop_lock EXIT
  trap 'drop_lock; exit 130' INT TERM
  _LOCK_TEXT="$text"
  _LOCK_HELD=()
  if [ "${PYJVM_LOCK_EXCLUSIVE:-0}" = 1 ]; then
    for ((i = 1; i <= n; i++)); do
      f=$(_lock_slot_file $i)
      until _lock_try "$f" "$text"; do
        [ -d "$(dirname "$f")" ] || { echo "cannot create $f" >&2; exit 2; }
        sleep 30
      done
      _LOCK_HELD+=("$f")
    done
    return 0
  fi
  while true; do
    for ((i = 1; i <= n; i++)); do
      f=$(_lock_slot_file $i)
      if _lock_try "$f" "$text"; then _LOCK_HELD=("$f"); return 0; fi
    done
    [ -d "$(dirname "$PYJVM_LOCK")" ] || { echo "cannot create $PYJVM_LOCK" >&2; exit 2; }
    sleep 30
  done
}

drop_lock() {
  local f
  if [ -n "$_LOCK_TEXT" ]; then
    for f in "${_LOCK_HELD[@]}"; do
      [ "$(cat "$f" 2>/dev/null)" = "$_LOCK_TEXT" ] && rm -f "$f"
    done
  fi
  _LOCK_TEXT=""
  _LOCK_HELD=()
  trap - EXIT INT TERM
}
