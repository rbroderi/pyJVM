#!/bin/bash
# usage: run.sh script.py prefix
for v in 313 314 315; do
  (cd /tmp && PYTHONUNBUFFERED=1 timeout 120 ~/pyjvm-n0/.venv$v/bin/python /mnt/e/pyjvm-work/n1/$1 > /mnt/e/pyjvm-work/n1/$2-$v.txt 2>&1 < /dev/null; echo "lane $v rc=$?")
done
