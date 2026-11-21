#!/bin/bash
# usage: conf_cases.sh case1 case2 ...   (runs each conformance case on lanes 315 314 313, each under its own interpreter)
# Env: PYJVM_TREE (default ~/pyjvm), LANES (default "315 314 313"), PYJVM_POSIX_FDS is inherited untouched.
# Takes the shared run lock ($PYJVM_LOCK, see run_lock.sh) for each case run itself.
. ~/pyjvm-env.sh
TREE=${PYJVM_TREE:-$HOME/pyjvm}
cd "$TREE" || exit 1
export PYTHONPATH="$TREE"
W=~/pyjvm-work
N=$(basename "$TREE")
. "$(dirname "$0")/run_lock.sh"
# Full output of every run goes to $W/logs/cases-$N/<lane>-<case>.out; a directory case (tests/conformance/<c>/) is run as such.
O=$W/logs/cases-$N
mkdir -p "$O"
for lane in ${LANES:-315 314 313}; do
  PYX="$TREE/.venv$lane/bin/python"
  export PYJVM_BUILD_CACHE=$W/bc-$N-conf-$lane PYJVM_CACHE=$W/c-$N-conf-$lane
  for c in "$@"; do
    target="tests/conformance/$c.py"
    [ -d "tests/conformance/$c" ] && target="tests/conformance/$c"
    take_lock "conf_cases $N $lane $c"
    # SIGINT back to default: a background job from a non-interactive bash inherits SIG_IGN, and CPython (the reference)
    # keeps it, so signal_basic's getsignal(SIGINT) line would differ (HANDOFF "Environment lessons")
    perl -e '$SIG{INT}="DEFAULT"; exec @ARGV' "$PYX" -m pyjvm315.conformance "$target" --python "$PYX" --selfhosted > "$O/$lane-$c.out" 2>&1
    drop_lock
    tail -3 "$O/$lane-$c.out" | sed "s/^/[$lane $c] /"
    grep -qE '^(FAIL|ERROR)' "$O/$lane-$c.out" && grep -E '^(FAIL|ERROR|CPython:|JVM)' "$O/$lane-$c.out" | cut -c1-400 | sed "s/^/[$lane $c] /"
  done
done
