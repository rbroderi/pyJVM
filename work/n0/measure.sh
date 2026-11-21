#!/bin/bash
# Run tools/posix_measure.py under the three Linux CPythons (CPython only, no lock needed).
# OUT: where the measure-31x.txt files go (default: this script's directory); TREE: the clone with the tools and venvs.
TREE=${PYJVM_TREE:-$HOME/pyjvm-n0}
OUT=${OUT:-$(cd "$(dirname "$0")" && pwd)}
for v in 313 314 315; do
  timeout 300 "$TREE/.venv$v/bin/python" "$TREE/tools/posix_measure.py" > "$OUT/measure-$v.txt" 2> "$OUT/measure-$v.err"
  echo "$v exit $? lines $(wc -l < "$OUT/measure-$v.txt")"
done
"$TREE/.venv313/bin/python" "$TREE/tools/gen_posix_tables.py" | sed -n '1,4p;133,135p'
