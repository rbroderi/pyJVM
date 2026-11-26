# N1 ticket 11: CPython's own tests, performance, triage

Host: WSL Ubuntu 24.04 (glibc 2.39, kernel 6.6.87.2), Temurin 25.0.5.1. Lane 3.15 (uv CPython 3.15.2), kernel
descriptors. Corpus `~/cpython/Lib` at `5b28ebd` (3.15 branch). Tree `n1-proc` at `fd0ed93` (tickets 0-10), clean.
Runner `tools/run_cpython_test.py` (CPython and pyJVM per-test status compared), unchanged. Run 2026-11-25.
Scripts and raw outputs: `pyjvm-work\n1\t11\` (`cpy.sh`, `start.sh`, `an.py`, `table.py`, `fmt.py`, `perf.py`,
`perf.sh`, `probe*.py`, shims) and WSL `~/pyjvm-work/t11/r1` (official run), `r2`, `r3` (triage runs below).

## Runs

- **r1 (official):** every selected class through `run_cpython_test.py`, CPython then pyJVM, cwd an empty directory,
  `sys.executable` = `"pyjvm"` as shipped (U7). `test_os.test_posix` cannot be imported by pyJVM (see below); its two
  classes were also run through `shim_posix.py` (`posixs`), which gives the `os` module a stand-in
  `sched_getscheduler` for one class-level `skipIf` only (`posix` untouched, so `requires_sched` stays False).
- **r2 (triage only):** pyJVM again with `pyjvm` on `PATH` as a symlink to the reference CPython, so that every
  "blocked: sys.executable" test actually starts its child; its result is shown after each such line ("stand-in").
- **r3 (triage only):** `shim_exe.py` sets `sys.executable` to the absolute CPython path: test_posix
  `TestPosixSpawn`/`TestPosixSpawnP` 43 ok / 0 fail / 6 skipped (the 4 scheduler tests and 2 `setsid` tests; CPython
  also skips `setsid`), `test_subprocess` `test_cwd*`, `test_executable*`, `test_args_string`, `test_call_string`
  13/13 ok. So every test that r2 left failing because the path is relative passes with a real executable.
- Safety: every run under the run lock and `timeout`, SIGHUP/SIGQUIT reset, started in its own session; after each
  module the leftover processes of that session (only test grandchildren: a `sleep 3` from
  `test_run_with_shell_timeout_and_capture_output`, a 0.2 s stand-in child) were killed by pid. The selected tests
  signal only their own children or their own pid. No hs_err file.

## Per module (r1; pyJVM vs CPython reference)

| module (selection) | tests | pyJVM ok / FAIL / ERROR / skip | CPython ok / FAIL / ERROR / skip |
|---|---|---|---|
| test_subprocess ProcessTestCase | 111 | 22 / 1 / 79 / 9 | 103 / 0 / 0 / 8 |
| test_subprocess POSIXProcessTestCase | 78 | 20 / 3 / 46 / 9 | 72 / 0 / 0 / 6 |
| test_subprocess RunFuncTestCase | 21 | 7 / 0 / 13 / 1 | 20 / 0 / 0 / 1 |
| test_subprocess MiscTests + ContextManagerTests | 10 | 4 / 0 / 6 / 0 | 10 / 0 / 0 / 0 |
| **test_subprocess (5 classes)** | 220 | **53 / 4 / 144 / 19** | 205 / 0 / 0 / 15 |
| test_os.test_posix TestPosixSpawn + TestPosixSpawnP | 49 | import error (unshimmed); shim: 12 / 0 / 31 / 6 | 47 / 0 / 0 / 2 |
| test_os.test_os PidTests + SpawnTests | 23 | 0 / 0 / 4 / 19 | 20 / 0 / 0 / 3 |
| test_wait3, test_wait4 | 5 | module skipped (`requires working os.fork()`) | 5 / 0 / 0 / 0 |
| test_popen | 5 | 4 / 0 / 1 / 0 | 5 / 0 / 0 / 0 |
| test_signal PendingSignalsTests | 14 | 2 / 0 / 7 / 5 | 14 / 0 / 0 / 0 |

With the r2 stand-in, the pyJVM figures become: test_subprocess 154 / 4 / 13 / 19 (the 15 non-sys.executable
failures below plus test_leak_fast_process_del_killed and test_zombie_fast_process_del), test_posix (shim)
27 / 1 / 15 / 6 (all 16 pass in r3), test_os 1 / 0 / 3 / 19, test_popen 5 / 0 / 0 / 0, test_signal 8 / 0 / 1 / 5.

**`SpawnTests`:** all 17 skipped under pyJVM, as expected (`os.spawn*` absent, D2).

**test_os.test_posix import error:** `AttributeError: module 'os' has no attribute 'sched_getscheduler'` from the
class-level `@unittest.skipIf(platform.libc_ver()[0] == 'glibc' and os.sched_getscheduler(0) in [...])` on
`test_setscheduler_with_policy`; CPython's test assumes the scheduler API on glibc. Out of scope: `sched_*` is not
part of N1 (the `scheduler=` argument raises NotImplementedError, plan 1.4/3.6). The module counts above are the shim's.

## Summary of the failure labels (r1)

| label | test_subprocess | test_posix (shim) | test_os | test_popen | test_signal | total |
|---|---|---|---|---|---|---|
| fixed here | 0 | 0 | 0 | 0 | 0 | **0** |
| **blocked: sys.executable** | 136 | 31 | 1 | 1 | 6 | **175** |
| blocked: fork | 0 | 0 | 3 | 0 | 0 | 3 |
| blocked: spawn helper | 8 | 0 | 0 | 0 | 0 | 8 |
| blocked on N2 | 0 | 0 | 0 | 0 | 0 | 0 |
| blocked on N3 | 1 | 0 | 0 | 0 | 0 | 1 |
| out of scope | 3 | 0 | 0 | 0 | 1 | 4 |

(+ test_wait3/test_wait4 whole modules skipped: blocked: fork; + the test_posix import error: out of scope.)

**Owner Q4: 175 tests are blocked only by `sys.executable`** (`"pyjvm"`, not runnable). With a runnable stand-in
173 of them pass (r2 + r3); the other 2 (`test_leak_fast_process_del_killed`, `test_zombie_fast_process_del`) then
fail because `Popen.__del__` does not run inside `support.gc_collect()` (no ResourceWarning; core GC timing, not N1).
No N1 defect was found behind the sys.executable label.

**Fixes:** none. No failure traced to code of tickets 2-10, so there is no "fixed here" line and no runtime diff.

## Skip differences (not failures)

- test_subprocess `test_pipesize_default`, `test_pipesizes`: `fcntl.F_GETPIPE_SZ` absent (fcntl: N2).
- `test_no_leaking`: "failed to reach the file descriptor limit": `resource.setrlimit` is a no-op stub and
  `getrlimit` returns a fixed tuple (`StubModules.resource`, a pre-existing placeholder; resource is N2), and the
  JVM's RLIMIT_NOFILE is 1048576 (plan 1.6 b).
- `test_preexec_fork_failure`: `resource.RLIMIT_NPROC` absent (N2); `test_fork_exec`,
  `test_fork_exec_sorted_fd_sanity_check`: `@cpython_only`; `test_group`, `test_user`: `os.setregid`/`setreuid`
  absent (plan 3.4), so `test_group_error`/`test_user_error` run and pass instead (CPython skips those).
- `test_executable_with_cwd`/`_without_cwd`: CPython skips them in a venv (`sys.base_prefix != sys.prefix`); pyJVM
  runs them (fail in r1 on sys.executable, pass in r2).
- test_posix `test_setscheduler_only_param`/`_with_policy` (x2): scheduler API absent (out of N1).
- test_signal `test_sigtimedwait*`, `test_sigwaitinfo`: `signal.sigtimedwait`/`sigwaitinfo` absent (signal module,
  not N1).
- `SpawnTests` (17), test_wait3/test_wait4 (module): no fork (D2).

## Every failure, classified (r1; "stand-in" = the same test in r2)

### test_subprocess
- `ProcessTestCase.test_bufsize_equal_one_binary_mode` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_bufsize_equal_one_text_mode` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_bytes_executable` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: b'pyjvm'; stand-in: ok
- `ProcessTestCase.test_bytes_executable_replaces_shell` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: b'pyjvm'; stand-in: ok
- `ProcessTestCase.test_call_kwargs` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_call_seq` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_call_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_call_nonzero` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_input_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_input_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_input_none_encoding_errors` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_input_none_text` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_input_none_universal_newlines` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_nonzero` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_stderr` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_stdin_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_check_output_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_class_getitems` FAIL: out of scope: `classmethod(types.GenericAlias)` as `__class_getitem__` does not give a `types.GenericAlias` (core runtime, not N1)
- `ProcessTestCase.test_communicate` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_eintr` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_errors` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_memoryview_input` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_memoryview_input_nonbyte` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_pipe_buf` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_returns` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_stderr` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_stdin` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_stdin_closed_before_call` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_stdout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_timeout_large_input` ERROR: blocked on N3: the test opens a loopback TCP server (`socket.create_server`)
- `ProcessTestCase.test_communicate_timeout_large_output` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_communicate_timeout_resume_partial_write` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_cwd` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_cwd_with_absolute_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: '/home/ubuntu/pyjvm-work/t11/cwd/pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: '/home/ubuntu/pyjvm-work/t11/cwd/pyjvm' (the test derives a path from sys.executable, relative here)
- `ProcessTestCase.test_cwd_with_bytes` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_cwd_with_pathlike` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_cwd_with_relative_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: './pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: './pyjvm' (the test derives a path from sys.executable, relative here)
- `ProcessTestCase.test_cwd_with_relative_executable` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: './pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: './pyjvm' (the test derives a path from sys.executable, relative here)
- `ProcessTestCase.test_env` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_executable` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_executable_replaces_shell` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_executable_takes_precedence` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_executable_with_cwd` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_executable_without_cwd` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_invalid_env` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_issue8780` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_pathlike_executable` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: <FakePath 'pyjvm'>; stand-in: ok
- `ProcessTestCase.test_pathlike_executable_replaces_shell` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: <FakePath 'pyjvm'>; stand-in: ok
- `ProcessTestCase.test_poll` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_post_timeout_communicate_sends_input` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_devnull` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_filedes` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_fileobj` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_pipe` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stderr_redirect_with_no_stdout_redirect` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdin_devnull` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdin_filedes` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdin_fileobj` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdin_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdin_pipe` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_devnull` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_filedes` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_filedes_of_stdout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_fileobj` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_pipe` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_stderr_file` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_stdout_stderr_pipe` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_threadsafe_wait` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_timeout_exception` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_universal_newlines_and_text` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_universal_newlines_communicate` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_universal_newlines_communicate_encodings` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_universal_newlines_communicate_stdin` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_universal_newlines_communicate_stdin_stdout_stderr` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_wait_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ProcessTestCase.test_writes_before_communicate` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_args_string` FAIL: blocked: sys.executable: a shell script execs sys.executable: exit 127; stand-in: ok
- `POSIXProcessTestCase.test_call_string` FAIL: blocked: sys.executable: a shell script execs sys.executable: exit 127; stand-in: ok
- `POSIXProcessTestCase.test_close_fd_0` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fd_1` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fd_2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds_0_1` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds_0_1_2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds_0_2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds_1_2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_close_fds_after_preexec` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_close_fds_when_max_fd_is_lowered` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_communicate_BrokenPipeError_stdin_close_with_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_communicate_BrokenPipeError_stdin_flush` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_communicate_repeated_call_after_stdout_close` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_exception_errpipe_bad_data` ERROR: out of scope: same mock `__call__` cause as test_exception_errpipe_normal
- `POSIXProcessTestCase.test_exception_errpipe_normal` ERROR: out of scope: pyJVM function objects have no `__call__` attribute, so `mock._callable(f)` is False and the mock calls `next()` on its function side_effect (core runtime; reproduced by `probe2.py` without subprocess)
- `POSIXProcessTestCase.test_kill` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_kill_dead` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_leak_fast_process_del_killed` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: FAIL: AssertionError: filter ('', ResourceWarning) did not catch any warning (Popen.__del__ not run by gc_collect: core GC timing, out of scope)
- `POSIXProcessTestCase.test_pass_fds` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_pass_fds_inheritable` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_pass_fds_redirected` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_pipe_cloexec` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_pipe_cloexec_real_tools` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_preexec` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_preexec_at_exit` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_preexec_errpipe_does_not_double_close_pipes` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_preexec_exception` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_preexec_gc_module_failure` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_process_group_0` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_remapping_std_fds` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_restore_signals` FAIL: blocked: spawn helper: posix_spawn cannot set SIG_IGN and the JVM's SIGPIPE/SIGXFSZ are handlers, so a `restore_signals=False` child gets them as default, equal to `restore_signals=True` (plan 1.6 gap a)
- `POSIXProcessTestCase.test_run_abort` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_select_unbuffered` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_send_signal` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_send_signal_dead` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_send_signal_race2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_small_errpipe_write_fd` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_start_new_session` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_surrogates_error_message` ERROR: blocked: spawn helper: `RuntimeError` (preexec_fn needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_swap_fds` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_swap_std_fds_with_one_closed` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_terminate` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_terminate_dead` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_umask` ERROR: blocked: spawn helper: `NotImplementedError` (umask needs code in the child, plan 1.7)
- `POSIXProcessTestCase.test_undecodable_env` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_wait_when_sigchild_ignored` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `POSIXProcessTestCase.test_zombie_fast_process_del` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: FAIL: AssertionError: filter ('', ResourceWarning) did not catch any warning (Popen.__del__ not run by gc_collect: core GC timing, out of scope)
- `RunFuncTestCase.test_capture_output` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_capture_stderr` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_capture_stdout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_check` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_check_output_input_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_check_output_stdin_arg` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_check_output_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_encoding_warning` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_returncode` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_run_kwargs` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_run_with_bytes_path_and_arguments` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: b'pyjvm'; stand-in: ok
- `RunFuncTestCase.test_run_with_pathlike_path_and_arguments` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: <FakePath 'pyjvm'>; stand-in: ok
- `RunFuncTestCase.test_timeout` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ContextManagerTests.test_communicate_stdin` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ContextManagerTests.test_pipe` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `ContextManagerTests.test_returncode` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `MiscTests.test_call_keyboardinterrupt_no_kill` ERROR: blocked: sys.executable: FileNotFoundError 'pyjvm' inside assertRaises(KeyboardInterrupt), then `[].pop()` in the test's finally (pyJVM's IndexError text is Java's, core runtime); stand-in: ok
- `MiscTests.test_context_manager_keyboardinterrupt_no_kill` ERROR: blocked: sys.executable: FileNotFoundError 'pyjvm' inside assertRaises(KeyboardInterrupt), then `[].pop()` in the test's finally (pyJVM's IndexError text is Java's, core runtime); stand-in: ok
- `MiscTests.test_run_keyboardinterrupt_no_kill` ERROR: blocked: sys.executable: FileNotFoundError 'pyjvm' inside assertRaises(KeyboardInterrupt), then `[].pop()` in the test's finally (pyJVM's IndexError text is Java's, core runtime); stand-in: ok

### test_os.test_posix (shim)
- `TestPosixSpawn.test_close_file` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_dup2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_empty_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_multiple_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_none_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_open_file` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_resetids` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_resetids_explicit_default` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_returns_pid` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_scheduler_allow_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_setpgroup` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_setpgroup_allow_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_setsigdef` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_setsigmask` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawn.test_specify_environment` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ERROR: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm' (posix_spawn does not search PATH)
- `TestPosixSpawnP.test_close_file` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_dup2` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_empty_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_multiple_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_none_file_actions` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_open_file` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_posix_spawnp` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: FAIL: FileNotFoundError: [Errno 2] No such file or directory: 'posix_spawnp_test_program.exe' (the test symlinks to the relative sys.executable)
- `TestPosixSpawnP.test_resetids` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_resetids_explicit_default` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_returns_pid` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_scheduler_allow_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_setpgroup` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_setpgroup_allow_none` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_setsigdef` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_setsigmask` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `TestPosixSpawnP.test_specify_environment` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok

### test_os.test_os
- `PidTests.test_getppid` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PidTests.test_waitpid` ERROR: blocked: fork: `os.P_NOWAIT`/`os.spawnv` absent (D2; os.py defines spawn* only with fork); also needs sys.executable
- `PidTests.test_waitstatus_to_exitcode` ERROR: blocked: fork: `os.P_NOWAIT`/`os.spawnv` absent (D2; os.py defines spawn* only with fork); also needs sys.executable
- `PidTests.test_waitstatus_to_exitcode_kill` ERROR: blocked: fork: `os.P_NOWAIT`/`os.spawnv` absent (D2; os.py defines spawn* only with fork); also needs sys.executable

### test_wait3/test_wait4/test_popen
- `PopenTest.test_popen` ERROR: blocked: sys.executable: `os.popen(sys.executable ...)` prints nothing, `eval('')`; stand-in: ok

### test_signal
- `PendingSignalsTests.test_pthread_kill` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PendingSignalsTests.test_pthread_kill_main_thread` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PendingSignalsTests.test_pthread_sigmask` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PendingSignalsTests.test_pthread_sigmask_arguments` ERROR: out of scope: `_signal.pthread_sigmask(SIG_BLOCK, [1<<1000])` raises OverflowError, CPython ValueError (SignalModule, N0 signal code, shared)
- `PendingSignalsTests.test_sigpending` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PendingSignalsTests.test_sigwait` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok
- `PendingSignalsTests.test_sigwait_thread` ERROR: blocked: sys.executable: FileNotFoundError: [Errno 2] No such file or directory: 'pyjvm'; stand-in: ok

## Performance (3.15 lane; `perf.py`, 3 rounds, CPython and pyJVM alternating, sequential, under the run lock)

Same machine state for all rounds (a foreign ~1-CPU process was running in WSL throughout; load average ~2 on 32
CPUs). pyJVM warm = inside one JVM after 50 (b: 20) warm-up calls. Per round, CPython / pyJVM:

| measurement | round 1 | round 2 | round 3 | mean | ratio |
|---|---|---|---|---|---|
| (a) `subprocess.run(["/bin/true"])` x200, ms/call | 0.634 / 1.487 | 0.642 / 1.383 | 0.643 / 1.480 | 0.640 / 1.450 | 2.27x |
| (b) same, 500 extra descriptors open, ms/call | 2.631 / 1.367 | 2.546 / 1.472 | 2.660 / 1.391 | 2.612 / 1.410 | 0.54x |
| (c) `subprocess.run(["cat"], input=64 MiB, capture_output=True)`, s | 0.224 / 1.398 | 0.212 / 1.367 | 0.238 / 1.452 | 0.225 / 1.406 | 6.3x |

(a) is under the 3x line. (b): pyJVM's spawn closes everything above the kept descriptors with one
`addclosefrom_np` file action, so 500 extra descriptors cost it nothing measurable; CPython 3.15 is slower here.
(c) is the `communicate()` loop (poll plus small writes and reads in Python, thousands of iterations), not the
spawn; not investigated further (no threshold in the plan).

## Side findings (not N1; for the lead)

- Core runtime: function objects have no `__call__` attribute (`getattr(f, "__call__", None)` is None), which breaks
  `unittest.mock` side_effect functions; `[].pop()` raises IndexError with Java's text ("Index -1 out of bounds for
  length 0", CPython "pop from empty list"); `classmethod(types.GenericAlias)` subscription is not a GenericAlias;
  `Popen.__del__` not run by `gc_collect()`.
- `SignalModule`: `pthread_sigmask` gives OverflowError for an out-of-range signal int (CPython ValueError).
- `StubModules.resource` `getrlimit`/`setrlimit` are fixed-value/no-op placeholders (N2 should replace them).
