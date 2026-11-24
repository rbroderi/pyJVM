#!/bin/bash
# usage: runcase.sh case-name   (runs twice per lane, reports stability)
for v in 313 314 315; do
  for i in 1 2; do
    (cd /tmp && PYTHONUNBUFFERED=1 timeout 180 ~/pyjvm-n0/.venv$v/bin/python /mnt/e/pyjvm-work/n1/cases/$1.py > /mnt/e/pyjvm-work/n1/cases/$1-$v-$i.out 2> /mnt/e/pyjvm-work/n1/cases/$1-$v-$i.err < /dev/null; echo "lane $v run $i rc=$?")
  done
  cmp /mnt/e/pyjvm-work/n1/cases/$1-$v-1.out /mnt/e/pyjvm-work/n1/cases/$1-$v-2.out && echo "lane $v stable"
done
