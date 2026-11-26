#!/bin/bash
# CPython-only runner for the ticket 7 measurement (no JVM, no run lock). New session, stdin /dev/null, timeout.
cd ~/pyjvm-n2t7/work/n2/measure || exit 1
for v in 313 314 315; do
  setsid timeout 120 ~/pyjvm-n0/.venv$v/bin/python termios_order.py > termios_order-$v.txt 2>&1 < /dev/null
  echo "$v rc=$?"
done
wc -l termios_order-*.txt
echo "--- diff 313 314"; diff termios_order-313.txt termios_order-314.txt | head -40
echo "--- diff 314 315"; diff termios_order-314.txt termios_order-315.txt | head -40
