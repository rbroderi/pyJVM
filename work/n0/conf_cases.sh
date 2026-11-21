#!/bin/bash
# usage: conf_cases.sh case1 case2 ...   (runs each conformance case on lanes 315 314 313, each under its own interpreter)
# Env: PYJVM_TREE (default ~/pyjvm), LANES (default "315 314 313"), PYJVM_POSIX_FDS is inherited untouched.
# Takes the shared run lock for each case run itself.
. ~/pyjvm-env.sh
TREE=${PYJVM_TREE:-$HOME/pyjvm}
cd "$TREE" || exit 1
export PYTHONPATH="$TREE"
LOCK=/mnt/c/Users/richa/workspace_local/pyjvm-work/run.lock
W=~/pyjvm-work
N=$(basename "$TREE")
take_lock() { until ( set -o noclobber; echo "conf_cases $N $1 $(date)" > "$LOCK" ) 2>/dev/null; do sleep 30; done; }
drop_lock() { rm -f "$LOCK"; }
for lane in ${LANES:-315 314 313}; do
  PYX="$TREE/.venv$lane/bin/python"
  export PYJVM_BUILD_CACHE=$W/bc-$N-conf-$lane PYJVM_CACHE=$W/c-$N-conf-$lane
  for c in "$@"; do
    take_lock "$lane $c"
    trap drop_lock EXIT
    "$PYX" -m pyjvm315.conformance "tests/conformance/$c.py" --python "$PYX" --selfhosted 2>&1 | tail -3 | sed "s/^/[$lane $c] /"
    drop_lock
    trap - EXIT
  done
done
