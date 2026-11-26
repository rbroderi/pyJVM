#!/bin/bash
# Draft conformance cases under CPython only (no JVM): twice per lane, with no controlling terminal and stdin from
# /dev/null (setsid), so nothing can reach a real terminal.
cd /mnt/e/pyjvm-work/n2/cases
for c in ${@:-fcntl_basic resource_basic pwd_grp_basic termios_pty}; do
  for v in 313 314 315; do
    for i in 1 2; do
      setsid timeout 120 ~/pyjvm-n0/.venv$v/bin/python $c.py > $c-$v-$i.out 2> $c-$v-$i.err < /dev/null; rc=$?
      [ $rc -ne 0 ] && echo "$c $v run $i rc=$rc" && tail -3 $c-$v-$i.err
    done
    cmp -s $c-$v-1.out $c-$v-2.out && echo "$c $v stable ($(wc -l < $c-$v-1.out) lines, stderr $(wc -c < $c-$v-1.err) bytes)" || { echo "$c $v UNSTABLE"; diff $c-$v-1.out $c-$v-2.out | head -6; }
    rm -f $c-$v-2.out $c-$v-2.err; [ -s $c-$v-1.err ] || rm -f $c-$v-1.err
  done
  echo "  lanes 313/314 differ in $(diff $c-313-1.out $c-314-1.out | grep -c '^<') lines, 314/315 in $(diff $c-314-1.out $c-315-1.out | grep -c '^<') lines"
done
pgrep -u $(id -u) -x sleep > /dev/null && echo "LEFTOVER sleep processes: $(pgrep -u $(id -u) -x sleep | tr '\n' ' ')" || echo "no leftover sleep"
