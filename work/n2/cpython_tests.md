# N2 ticket 8: CPython's own tests, performance, triage

Host: WSL Ubuntu 24.04 (glibc 2.39), lane 3.15 (uv CPython 3.15.2), kernel descriptors. Corpus `~/cpython/Lib` at
`5b28ebd`. Tree `~/pyjvm-n0` = branch `n2` at `347cc14` (tickets 0-7 + WarningsModule), no runtime change: clean for
r1 and the triage runs; the final timing run had this ticket's doc diff (this file, HANDOFF.md) applied. Run 2026-11-26.
Scripts and raw outputs: `E:\pyjvm-work\n2\t8\` (`cpy.sh`, `tri.sh`, `perf.sh`, `perf.py`, `cpyx.py`, `regrx.py`,
`m_refcount.py`, `probe_own.py`, `an2.py`, logs `r1.log`, `tri.log`, `perf3.log`; `perf.log`/`perf2.log` are the
superseded first timing runs) and WSL `~/pyjvm-work/n2t8/r1`
(official), `tri` (triage).

## How the runs were made (plan 2.3)

- `tools/run_cpython_test.py` unchanged, driven by `cpyx.py`, which only swaps the in-process runner for `regrx.py`
  (= `tools/pyjvm_regrtest.py` plus `-x NAME`, an exact method-name exclusion; unittest `-k` patterns are wrapped in
  `*...*` and cannot exclude `test_prlimit_refcount` while selecting `test_prlimit`). `regrx.py` **always** drops
  `test_preexec_fork_failure`, whatever the arguments; it was also never selected.
- `cpy.sh` refuses to run unless it leads its own session (`setsid`) with stdin not a terminal (started `< /dev/null`),
  and not as root; one run-lock slot for the whole script; every run under `timeout` with SIGINT/SIGHUP/SIGQUIT reset;
  after each run the leftover processes of that session are listed and killed by pid (none occurred in any run).
  No hs_err/replay/core file. Leftover test temp dirs (pathlib/tarfile rmtree failures, below) were removed from the
  script's own scratch cwd.
- `test_resource`: first everything except the five `test_fsize_*` and the two `*_refcount` tests (`-x`); then a
  warm-up run of the same command (`-k .test_pagesize test_resource`); then each `test_fsize_*` in its own fresh JVM
  (stdout/stderr of the JVM are `run_cpython_test`'s pipes). `test_no_leaking`: alone (`-k .ProcessTestCase.test_no_leaking`),
  the very last run, fresh JVM, `--timeout 600`.
- **Not in the plan's inventory (3.9), found here:** `test_setrusage_refcount` is not rejected before the call: its
  `BadSequence` converts to `(1000000, 1000000)`, so it lowers the **hard** `RLIMIT_CPU` of the process running it to
  10^6 s; `test_prlimit_refcount` sets `RLIMIT_AS` soft and hard to `RLIM_INFINITY - 1` (hard lowered). Both violate
  2.3 B1 inside a JVM, so they were **not run**. Instead `m_refcount.py` passes the same objects to
  `resource.prlimit(child, ...)` on its own `sleep 30` child (setrlimit and prlimit share the converter on both
  interpreters: `py2rlimit` / `PosixResourceModule.limitsInto`): CPython and pyJVM print identical lines (CPU
  `(1000000, 1000000)`, AS `(2**64-2, 2**64-2)`), child killed and reaped. Ticket 9 should correct plan 3.9.
- No controlling terminal (A6): `test_ioctl.IoctlTestsTty` is skipped on both lanes (setUpClass cannot open
  `/dev/tty`); `test_pty`'s `tcgetattr(STDIN_FILENO)` sees `/dev/null` and falls back on both.

## Per module (r1; ok / FAIL / ERROR / skipped)

| module (selection) | tests | pyJVM | CPython |
|---|---|---|---|
| test_pwd | 3 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| test_grp | 3 | 3 / 0 / 0 / 0 | 3 / 0 / 0 / 0 |
| test_openpty | 1 | 1 / 0 / 0 / 0 | 1 / 0 / 0 / 0 |
| test_tty | 4 | 4 / 0 / 0 / 0 | 4 / 0 / 0 / 0 |
| test_termios | 21 | 21 / 0 / 0 / 0 | 21 / 0 / 0 / 0 |
| test_ioctl | 4 + IoctlTestsTty class | 4 / 0 / 0 / class skipped | 4 / 0 / 0 / class skipped |
| test_fcntl | 14 | 7 / 2 / 2 / 3 | 11 / 0 / 0 / 3 |
| test_pty | 6 | 3 / 0 / 3 / 0 | 6 / 0 / 0 / 0 |
| test_getpass | 50 | 46 / 0 / 4 / 0 | 50 / 0 / 0 / 0 |
| test_resource (all but fsize/refcount) | 7 | 7 / 0 / 0 / 0 | 7 / 0 / 0 / 0 |
| test_resource `test_fsize_*`, one JVM each | 5 | 5 / 0 / 0 / 0 (no JVM died or hung) | 5 / 0 / 0 / 0 |
| test_resource `*_refcount` | 2 | unsafe: not run | (not run) |
| test_subprocess pipesize/user/group/extra_groups/max_fd | 12 | 3 / 0 / 5 / 4 | 8 / 0 / 0 / 4 |
| test_subprocess `test_no_leaking` (alone, last) | 1 | 0 / 0 / 1 / 0 | 1 / 0 / 0 / 0 |
| test_posixpath `-k expanduser` | 5 | 5 / 0 / 0 / 0 | 5 / 0 / 0 / 0 |
| test_pathlib.test_pathlib owner/group/expanduser | 36 | 0 / 0 / 12 / 24 | 12 / 0 / 0 / 24 |
| test_shutil `-k chown -k owner` | 3 | 0 / 0 / 2 / 1 | 3 / 0 / 0 / 0 |
| test_os.test_os FDInheritance + ChownFile + Pseudoterminal | 19 | 13 / 0 / 0 / 6 | 17 / 0 / 0 / 2 |
| test_tarfile `-k NumericOwnerTest -k uname -k gname -k ownership` | 46 | 6 / 0 / 23 / 5, 12 not run (setUpClass), tearDownModule ERROR | 46 / 0 / 0 / 0 |

The nine whole N2 modules: 95 of 106 tests agree; every N2 function exercised by them (pwd, grp, termios, tty,
os.openpty, ioctl on a pty, fcntl/flock/lockf/pipe sizes, resource incl. fsize) behaves as CPython's.
`test_no_leaking` now reaches the descriptor limit (N1 skipped it: "failed to reach the file descriptor limit") and
fails only when it starts `sys.executable`.

## Labels (differing tests)

| label | count |
|---|---|
| fixed here | 0 |
| blocked: fork | 5 |
| blocked: sys.executable | 6 |
| blocked: `_testcapi` | 0 (the two `_testcapi` fcntl tests skip on both lanes) |
| blocked: controlling terminal | 0 differing (IoctlTestsTty skipped on both) |
| blocked on N3 | 1 |
| out of scope (core runtime / `os` outside N2) | 68 (+ 2 fixture lines in test_tarfile) |
| unsafe: not run | 2 |

No difference traced to code of tickets 1-7, so nothing was fixed and there is no runtime diff.

## Every difference, classified

### test_fcntl
- `test_fcntl_small_buffer`, `test_fcntl_large_buffer` FAIL `0 != <pid>`: out of scope: `memoryview(r).cast('i')[1]`
  (and `[:2]`) returns a wrong element in pyJVM while `tolist()` is right; `fcntl(F_SETOWN_EX/F_GETOWN_EX)` itself
  returns the correct bytes (`probe_own.py`: `SETOWN_EX ret == buf`, `GETOWN_EX` unpacks to `(F_OWNER_PID, pid)` for
  8 and 2024 bytes). Core memoryview cast indexing.
- `test_lockf_exclusive`, `test_lockf_share` ERROR: blocked: fork: `multiprocessing.Process` (`_multiprocessing` absent).

### test_pty
- `test_fork`, `test_spawn_doesnt_hang` ERROR: blocked: fork (`pty.fork` -> `os.fork` absent).
- `SmallPtyTests.test__copy_to_each` ERROR: blocked on N3: `socket.socketpair` not supported.

### test_getpass
- `GetpassGetuserTest.test_username_falls_back_to_pwd`, `test_username_priorities_of_env_values`,
  `test_username_takes_username_from_env` ERROR `missing required positional argument: 'environ'`: out of scope: a
  class-level `@mock.patch` decorator does not pass the mock to the methods (core; the methods appear as
  `__py_nested_N_...`). The pwd fallback itself is covered by `pwd_grp_basic` (ticket 3).
- `UnixGetpassTest.test_falls_back_to_stdin` ERROR `'MagicMock' object has no attribute '__lt__'`: out of scope:
  `MagicMock._mock_set_magics` `delattr` of a magic method fails (core).

### test_subprocess
- `ProcessTestCase.test_pipesizes`, `test_pipesize_default`, `ProcessTestCaseNoPoll.test_pipesizes`,
  `test_pipesize_default` ERROR: blocked: sys.executable (`FileNotFoundError: 'pyjvm'`); they no longer skip
  (`F_GETPIPE_SZ` exists now).
- `POSIXProcessTestCase.test_close_fds_when_max_fd_is_lowered` ERROR: blocked: sys.executable.
- `ProcessTestCase.test_no_leaking` ERROR: blocked: sys.executable (after reaching EMFILE under soft limit 1024;
  limit restored by the test's cleanup; the JVM survived).
- `test_user`, `test_group` skipped (pyJVM) / ok (CPython) and `test_user_error`, `test_group_error` ok / skipped:
  out of scope: `os.setreuid`/`os.setregid` absent (N1 plan 3.4). `test_extra_groups*` skip on both.

### test_pathlib.test_pathlib (PathTest, PosixPathTest, PathSubclassTest x `test_owner`, `test_group`, `test_expanduser_common`, `test_expanduser_posix`)
- 12 ERROR: out of scope: the first test's `tearDown` (`os_helper.rmtree` -> `os.walk`) fails with `'DirEntry' object
  has no attribute 'is_junction'` (core `os.scandir`), the temp dir stays, every later `setUp` gets FileExistsError.
  Triage (`tri`, one test per JVM, `-v`): each of the four PosixPathTest bodies passes; the only error is that tearDown.

### test_shutil
- `test_make_archive_owner_group`, `test_tarfile_root_owner` ERROR `un(shallow)copyable object of type TarInfo`: out of
  scope: `copy.copy` of a `__slots__` class fails (core `__reduce_ex__`); `pwd.getpwuid(0)`/`grp.getgrgid(0)` ran fine.
- `TestMisc.test_chown` skipped: out of scope: `os.chown` absent ("requires os.chown").

### test_os.test_os
- `ChownFileTests.test_chown_gid`, `test_chown_uid_gid_arguments_must_be_index`, `test_chown_without_permission`
  skipped: out of scope: `os.chown` absent (class skip "requires os.chown()"; `pwd.getpwall()` at import worked).
- `PseudoterminalTests.test_pipe_spawnl` skipped: blocked: fork (`os.spawnl` absent, N1 D2). The other three
  PseudoterminalTests (`openpty`, `open_via_ptsname`, `posix_pty_functions`) and all FDInheritanceTests pass.
- Not run: the `pwd` users in `test_os/test_posix.py` (`test_initgroups`, `test_getgrouplist`); both would skip under
  pyJVM since `os.initgroups`/`os.getgrouplist` are absent (and the module does not import, N1 ticket 11).

### test_tarfile
- 23 ERROR (`GNUReadTest`/`PaxReadTest.test_longname_directory`/`_file_not_directory`, `GNUWriteTest`/`PaxWriteTest
  .test_longname(link)_1023/1024/1025`, `Ustar/GNU/PAXUnicodeTest.test_uname_unicode`, `UstarUnicodeTest
  .test_unicode_longname1-4`): out of scope: `copy.copy(TarInfo)` (core, as in test_shutil).
- `NoneInfoExtractTests_{Data,Default,FullyTrusted,Tar}` x `test_extractall_none_{uname,gname,ownership}` (12, not run)
  and their setUpClass errors: out of scope: same `copy.copy(TarInfo)` in `extractall`.
- `tearDownModule` ERROR: out of scope: `DirEntry.is_junction` (as pathlib).
- `ExtendedHeaderMemoryTest.test_gnu_longname_oversized_size` skipped: out of scope: `@support.cpython_only`
  (selected incidentally by `-k gname`).
- `NumericOwnerTest` (4) skipped: out of scope: `os.chown` absent (class `skipUnless(hasattr(os, 'chown'))`).

### test_resource
- `test_setrusage_refcount`, `test_prlimit_refcount`: unsafe: not run (lower hard limits, see above); equivalent
  prlimit-on-child check identical to CPython.

## Performance (3.15; `perf.py`, 3 rounds, CPython and pyJVM alternating, sequential, one lock slot)

One process per round. Each loop first runs untimed warm-up calls (50 000; (b) 5 000, (c) 500), so the JVM has
reached C2, then the timed calls. Every row calls through a Python `lambda` except `os.getpid` and `pwd.getpwall`
(passed directly). Descriptors are the script's own pipe and `os.openpty()` pair. Control rows added on review.
Microseconds per call, CPython / pyJVM (`perf3.log`):

| measurement | round 1 | round 2 | round 3 | mean | ratio |
|---|---|---|---|---|---|
| ctl `lambda: None` | 0.019 / 1.403 | 0.014 / 1.491 | 0.014 / 1.298 | 0.016 / 1.397 | 87x |
| ctl `lambda: len(())` | 0.024 / 1.263 | 0.020 / 1.019 | 0.022 / 1.226 | 0.022 / 1.169 | 53x |
| ctl `os.getpid` (no lambda) | 0.176 / 0.494 | 0.170 / 0.124 | 0.179 / 0.090 | 0.175 / 0.236 | 1.3x |
| ctl `lambda: os.get_inheritable(r)` | 0.245 / 0.884 | 0.239 / 0.907 | 0.241 / 0.866 | 0.242 / 0.886 | 3.7x |
| (a) 20 000 x `fcntl.fcntl(fd, F_GETFD)` | 0.276 / 1.976 | 0.267 / 1.553 | 0.271 / 1.714 | 0.271 / 1.748 | 6.4x |
| (b) 2 000 x `pwd.getpwuid(os.getuid())` | 6.65 / 15.62 | 7.00 / 16.09 | 6.69 / 16.31 | 6.78 / 16.01 | 2.4x |
| (c) 200 x `pwd.getpwall()` (28 entries) | 256.9 / 277.2 | 253.1 / 270.7 | 259.3 / 286.8 | 256.4 / 278.2 | 1.1x |
| (d) 2 000 x `termios.tcgetattr(slave)` | 0.596 / 1.230 | 0.610 / 1.203 | 0.605 / 1.175 | 0.604 / 1.203 | 2.0x |
| (e) 2 000 x `resource.getrlimit(RLIMIT_NOFILE)` | 0.303 / 2.851 | 0.301 / 0.843 | 0.398 / 0.721 | 0.334 / 1.472 | 4.4x (rounds 2-3: 2.2x) |

None is over 10x. The first timing run (`perf.log`, warm-up = n calls) gave (a) 8.2x, (d) 8.8x, (e) 7.8x; with a
C2-length warm-up (d) falls from 5.3 to 1.2 us, so the earlier figures measured an unwarmed JVM. The controls show
where the remaining time goes: an empty Python `lambda` call costs ~1.2-1.4 us in pyJVM against ~0.02 us in CPython,
while a direct builtin call (`os.getpid`) costs ~0.1 us once warm. So most of (a)/(d)/(e) is the generic Python
function-call path of the benchmark's lambda, not the FFM downcall (static final handles, thread-local errno:
tens of ns). Subtracting the empty-lambda row leaves roughly 0.35 us for (a) and 0-0.1 us for (d)/(e), close to
CPython's own cost. (e)'s first round did not settle (2.85 us). Not an N2 issue; the lambda-call cost is core.
Optional, ticket 9 or later (not implemented): `getrlimit` and `tcgetattr` open an `Arena.ofConfined()` per call
(could use the thread-local `PosixNative.Tl` buffer, as `PosixFds.ioBuf` does), and `PosixFcntlModule`'s `retry(() -> ...)` allocates a capturing lambda per call.

## Side findings (not N2; for the lead)

- Core: `memoryview.cast('i')` element indexing/slicing wrong past index 0 (tolist right); class-level `mock.patch`
  decorators do not pass the mock argument; `MagicMock` magic `delattr`; `copy.copy` of `__slots__` classes
  (`tarfile.TarInfo`), which breaks `tarfile` extraction filters and `shutil.make_archive(..., 'tar')`;
  `os.DirEntry.is_junction` missing, which breaks `os.walk` and so `shutil.rmtree` in 3.15.
- `os`: `chown`, `setreuid`/`setregid`, `setgroups` absent (outside N2).
- Plan 3.9: the two `test_resource` refcount tests lower hard limits (above).
