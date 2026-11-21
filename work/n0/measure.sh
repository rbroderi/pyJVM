#!/bin/bash
# Run tools/posix_measure.py under the three Linux CPythons (CPython only, no lock needed).
W=/mnt/c/Users/richa/workspace_local/pyjvm-work
for v in 313 314 315; do
  timeout 300 ~/pyjvm-n0/.venv$v/bin/python $W/wt-n0/tools/posix_measure.py > $W/n0/measure-$v.txt 2> $W/n0/measure-$v.err
  echo "$v exit $? lines $(wc -l < $W/n0/measure-$v.txt)"
done
~/pyjvm-n0/.venv313/bin/python $W/wt-n0/tools/gen_posix_tables.py | sed -n '1,4p;133,135p'
