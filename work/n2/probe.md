# N2 ticket 1: JVM-side probe

Plain JDK FFM (no pyJVM code), JDK 25.0.5.1 on WSL (Ubuntu 24.04, glibc 2.39), scratch only: `~/pyjvm-work/n2/probe/Probe.java`
(source kept at `E:\pyjvm-work\n2\t1\Probe.java`, runner `probe.sh`, outputs `outA.txt`/`outB.txt`; none of it is in the repo).
Run under the run lock, stdin `/dev/null`, two ways:

- **A** `setsid -w timeout 120 java ... Probe`: the JVM is a child of `timeout` (pid 273774, pgrp = session = 273773).
- **B** `setsid -w java ... Probe` (the JVM itself is the session leader: pid = pgrp = session = 273807, so this is the case
  in which an open of a terminal without `O_NOCTTY` could acquire a controlling terminal). The program has a 100 s
  watchdog thread instead of `timeout`.

Both runs gave identical results (exit 0, no `hs_err_pid*.log`, no stray processes).

SAFETY as in plan 2.3: the only terminal calls are on the pty the program opens itself (`posix_openpt` with
`O_RDWR | O_NOCTTY | O_CLOEXEC`); the only limit touched is the soft `RLIMIT_NOFILE`, minus one and back in `finally`;
no signal is sent; the flock test locks a temporary file the program created.

## (a) NSS inside the JVM (U1)
- `getpwuid_r(getuid())`: rc 0, entry found (uid 1000). `getpwnam_r("root")`: rc 0, uid 0.
- `getpwuid_r(4000000123)`: rc 0, **result pointer NULL** (not found is rc 0 with a NULL result cell). `getgrgid_r(0)`: found.
- `getpwuid_r(0)` with a 4-byte buffer: rc 34 (`ERANGE`), as plan 1.6 relies on.
- `getpwent`/`getgrent` enumeration from two Java threads under one lock, three rounds each, every name read and each
  `gr_mem` list walked: **28 passwd entries and 56 group entries on both threads**; `getent passwd | wc -l` = 28 and
  `getent group | wc -l` = 56 (same host, same moment). No crash, no `hs_err` (NSS `files systemd` works inside the JVM).
- Verdict: **(a) passes** (no crash, enumerates, counts equal `getent`).

## (b) `RLIMIT_NOFILE`
- Soft = hard = 1048576 (`soft == hard`, as expected: HotSpot has already raised the soft limit).
- `setrlimit(soft - 1, hard)` rc 0; restore rc 0; a following `getrlimit` shows soft and hard unchanged.
- No other limit was touched. Verdict: **(b) passes** (`setrlimit` works).

## (c) pty and the controlling terminal
- `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r` (`/dev/pts/N`), `open(O_RDWR | O_NOCTTY | O_CLOEXEC)` all succeed.
- `tcgetattr` on the slave: `[1280, 5, 191, 35387, 15, 15]` (iflag, oflag, cflag, lflag, ispeed, ospeed), the values of plan 3.5.
- Field 7 of `/proc/self/stat` (`tty_nr`) is **0 before opening, after opening the slave, after closing the master + 1 s,
  and at exit**, in both runs, including run B where the JVM is its own session leader.
- Closing the master delivered no SIGHUP: the JVM was alive after the one-second sleep and ran part (d) afterwards.
- Verdict: **(c) passes** (`tty_nr` stays 0, the JVM survives).

## (d) `flock` between two descriptions in one process
- `flock(fd1, LOCK_EX)` rc 0. `flock(fd2, LOCK_EX | LOCK_NB)` while fd1 holds: rc -1, errno 11 (`EAGAIN`).
- A second Java thread calling `flock(fd2, LOCK_EX)` (blocking) was **still blocked after 400 ms**; after
  `flock(fd1, LOCK_UN)` it returned 0 within the join (about 0 ms after the release).
- Verdict: **(d) passes**.

## Stop conditions
(a) no crash and not empty: not triggered. (c) `tty_nr` non-zero or JVM died: not triggered. (b) `setrlimit` failing: not
triggered. Work continued.

## Notes for later tickets
- `getpwuid_r`/`getgrgid_r`/`getpwnam_r` take `uid_t` as a 32-bit int: pass `(int) value` for ids above 2**31.
- The two `ent` enumerations did not interfere with the other JVM threads; the lock was held for the whole of one
  enumeration including the string copies.
