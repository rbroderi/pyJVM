#!/bin/bash
# C layout + libc symbol check. No JVM.
cd /mnt/e/pyjvm-work/n2
gcc -O0 -o /tmp/n2_layout layout.c && /tmp/n2_layout > layout.txt 2>&1; echo "layout rc=$?"
cat layout.txt
echo "--- symbols"
for s in fcntl fcntl64 ioctl flock lockf getrlimit setrlimit prlimit prlimit64 getrusage getpwuid_r getpwnam_r getgrgid_r getgrnam_r setpwent getpwent getpwent_r endpwent setgrent getgrent getgrent_r endgrent tcgetattr tcsetattr tcsendbreak tcdrain tcflush tcflow cfgetispeed cfgetospeed cfsetispeed cfsetospeed openpty posix_openpt grantpt unlockpt ptsname_r ttyname_r getgrouplist getgroups getlogin_r tcgetpgrp ctermid; do
  printf "%s: " $s; nm -D /lib/x86_64-linux-gnu/libc.so.6 | grep -E " [TWi] $s(@|$)" | awk '{print $2 $3}' | tr '\n' ' '; echo
done
ldd --version | head -1; uname -r; id -u; tty; ulimit -n; ulimit -Hn
echo "passwd lines $(wc -l < /etc/passwd) group lines $(wc -l < /etc/group)"; grep -E '^(passwd|group):' /etc/nsswitch.conf
