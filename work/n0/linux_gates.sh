#!/bin/bash
# Full gates on Linux, one at a time. A step whose log ends with a DONE line is skipped, so rerunning resumes.
# Tree: PYJVM_TREE (default ~/pyjvm). Logs: ~/pyjvm-work/logs/<tree-name>-<commit>/ so a rerun at another commit starts fresh.
# Each step takes the shared run lock (C:\...\pyjvm-work\run.lock) itself; do not call this under the lock.
# Steps: STEPS="pytest methodsize conf315 conf314 conf313 probe strict" (default: the first five).
. ~/pyjvm-env.sh
TREE=${PYJVM_TREE:-$HOME/pyjvm}
cd "$TREE" || exit 1
export PYTHONPATH="$TREE"
git log --oneline -1
W=~/pyjvm-work
L=$W/logs/$(basename "$TREE")-$(git rev-parse --short HEAD)
mkdir -p "$L"
LOCK=/mnt/c/Users/richa/workspace_local/pyjvm-work/run.lock
PY13="$TREE/.venv313/bin/python"
PY14="$TREE/.venv314/bin/python"
PY15="$TREE/.venv315/bin/python"

take_lock() {
  until ( set -o noclobber; echo "linux_gates $(basename "$TREE") $1 $(date)" > "$LOCK" ) 2>/dev/null; do sleep 30; done
}
drop_lock() { rm -f "$LOCK"; }

step() {   # step NAME command...   (env prefix via export before the call)
  local name=$1; shift
  if tail -1 "$L/$name.log" 2>/dev/null | grep -q '^DONE'; then echo "skip $name (done)"; return; fi
  take_lock "$name"
  trap drop_lock EXIT
  echo "start $name $(date +%T)" > "$L/$name.log"
  "$@" >> "$L/$name.log" 2>&1
  echo "DONE exit $? $(date +%T)" >> "$L/$name.log"
  drop_lock
  trap - EXIT
  echo "$name: $(tail -3 "$L/$name.log" | tr '\n' ' ')"
}

want() { case " ${STEPS:-pytest methodsize conf315 conf314 conf313} " in *" $1 "*) return 0;; esac; return 1; }

want pytest && step pytest "$PY13" -m pytest -q -p no:cacheprovider -rfE
want methodsize && step methodsize "$PY13" -m pyjvm315.methodsize --runtime
for lane in 315 314 313; do
  want conf$lane || continue
  PYX="$TREE/.venv$lane/bin/python"
  export PYJVM_BUILD_CACHE=$W/bc-$(basename "$TREE")-conf-$lane PYJVM_CACHE=$W/c-$(basename "$TREE")-conf-$lane
  step conf$lane "$PYX" -m pyjvm315.conformance tests/conformance --python "$PYX" --selfhosted
  unset PYJVM_BUILD_CACHE PYJVM_CACHE
done
if want probe; then
  step probe "$PY15" -m pyjvm315.regression_probe --manifest tools/cpython_probe_3000_manifest.txt --cpython-root ~/cpython --baseline tools/cpython_probe_3000_baseline.json --expect-cases 3000 --min-compiles 2918 --fail-on-error
fi
if want strict; then
  # resumable.py keeps per-piece outputs under --out; rerunning skips finished pieces
  export PYJVM_BUILD_CACHE=$W/strict-build-$(basename "$TREE")
  step strict "$PY15" tools/resumable.py strict --out $W/strict-units-$(basename "$TREE") --jobs 1 --python "$PY15" --cpython-root ~/cpython
fi
echo ALL-DONE
