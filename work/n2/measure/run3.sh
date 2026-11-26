#!/bin/bash
# Which m2 lines warn on 3.15, and a static inventory of CPython's own tests for the N2 modules. No JVM.
cd /mnt/e/pyjvm-work/n2
L=~/cpython/Lib/test
echo "testdir $L"
for f in test_fcntl test_ioctl test_termios test_resource test_pwd test_grp test_pty test_tty test_openpty test_getpass test_structseq; do
  p=$L/$f.py; [ -f $p ] || p=$L/$f
  echo "== $f: $(grep -c -E '^\s+def test_' $p 2>/dev/null) tests; classes: $(grep -E '^class ' $p | sed -E 's/class ([A-Za-z0-9_]+).*/\1/' | tr '\n' ' ')"
  grep -n -o -E 'os\.(fork|forkpty|login_tty|setsid|getpgrp|tcgetpgrp|tcsetpgrp|ctermid|getgroups|getlogin|posix_openpt|openpty|ttyname)\b|pty\.(fork|spawn|openpty)|sys\.executable|script_helper|assert_python_ok|multiprocessing|import_module\(.[a-z_]+.\)|requires_subprocess|requires_fork|threading_helper|signal\.(alarm|SIG[A-Z]+)|_testcapi|_testlimitedcapi|get_pagesize|struct_[a-z]+\(|pickle|F_[A-Z_]+|RLIMIT_[A-Z]+|termios\.[A-Z][A-Z0-9_]+' $p | sed -E 's/^[0-9]+://' | sort | uniq -c | sort -rn | head -40 | awk '{printf "%s(%s) ", $2, $1}'; echo
done
echo "== test_subprocess / test_os / test_posix / test_shutil / test_tempfile uses"
for f in test_subprocess test_os test_posix test_shutil test_tempfile test_pathlib test_getpass test_tarfile; do
  p=$L/$f.py; [ -f $p ] || p=$L/$f
  echo "$f: $(grep -r -n -o -E '\b(pwd|grp|resource|termios|fcntl|pty|tty)\.[A-Za-z_]+' $p | sed -E 's/^.*:[0-9]+://' | sort | uniq -c | sort -rn | head -30 | awk '{printf "%s(%s) ", $2, $1}')"
done
echo "== stdlib modules importing the five (Lib, not tests)"
S=~/cpython/Lib
grep -l -E '^\s*(import|from) (fcntl|termios|resource|pwd|grp)\b' -r $S --include=*.py --exclude-dir=test --exclude-dir=site-packages --exclude-dir=idlelib | sed "s|$S/||" | sort | tr '\n' ' '
echo
