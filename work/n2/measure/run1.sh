#!/bin/bash
# CPython-only measurement runner (no JVM, no run lock needed). Usage: run1.sh <script-stem>
cd /mnt/e/pyjvm-work/n2
s=${1:-m1}
for v in 313 314 315; do timeout 120 ~/pyjvm-n0/.venv$v/bin/python $s.py > $s-$v.txt 2>&1 < /dev/null; echo "$v rc=$?"; done
wc -l $s-*.txt
echo "--- diff 313 314"; diff $s-313.txt $s-314.txt | head -${2:-40}
echo "--- diff 314 315"; diff $s-314.txt $s-315.txt | head -${2:-40}
