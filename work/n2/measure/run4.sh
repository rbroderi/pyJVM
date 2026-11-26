#!/bin/bash
# Read-only look at CPython's tests (what they change in the process). No JVM, nothing executed.
L=~/cpython/Lib/test
cd ~/cpython && git log -1 --format='corpus %h %ad' --date=short
echo "== test_resource"; grep -n -E 'def test_|setrlimit|prlimit\(|skip|requires' $L/test_resource.py | cut -c1-170
echo "== test_fcntl"; grep -n -E 'def test_|skip|requires|Process|fork|import_module' $L/test_fcntl.py | cut -c1-170
echo "== test_ioctl"; grep -n -E 'def test_|skip|requires|/dev/tty|open\(|fork|class ' $L/test_ioctl.py | cut -c1-170
echo "== test_termios"; grep -n -E 'def test_|skip|requires|/dev/tty|fork|class |setUp|openpty' $L/test_termios.py | cut -c1-150
echo "== test_pwd"; grep -n -E 'def test_|skip|requires|class ' $L/test_pwd.py | cut -c1-150
echo "== test_grp"; grep -n -E 'def test_|skip|requires|class ' $L/test_grp.py | cut -c1-150
echo "== test_pty"; grep -n -E 'def test_|skip|requires|class |fork|/dev/tty|setsid|SIGHUP|alarm' $L/test_pty.py | cut -c1-150
echo "== test_tty"; grep -n -E 'def test_|skip|requires|class |openpty|/dev/tty' $L/test_tty.py | cut -c1-150
echo "== test_openpty"; cat $L/test_openpty.py | cut -c1-150
echo "== test_subprocess pty/termios/setsid users"; grep -n -E 'openpty|termios|import pty|os\.setsid|tcgetpgrp|getpgid|RLIMIT_NPROC|RLIMIT_NOFILE|F_GETPIPE_SZ|pwd\.|grp\.' $L/test_subprocess.py | cut -c1-170
echo "== test_getpass tty"; grep -n -E "/dev/tty|def test_.*(tty|termios|fallback)" $L/test_getpass.py | cut -c1-150
