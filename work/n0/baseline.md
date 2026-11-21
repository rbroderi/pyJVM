# N0 baseline at B0 (4405c12)

Known-failing set for N0 tickets: a later ticket passes its comparison when its failing set per lane and OS is this set or a subset. Taken 2026-11-17 at 4405c12 (Windows worktree `wt-n0`, WSL clone `~/pyjvm-n0`). The WSL tree was clean; the Windows tree held two untracked files, `tools/posix_measure.py` and `tools/gen_posix_tables.py` (the ticket-0 tools, then without licence headers), which caused the Windows `test_licenses` failure below. Logs: `work/n0/logs/{windows,linux}/`. Written from those logs on 2026-11-21.

## Hosts
- Windows 11: Temurin 25.0.5; lanes `.venv313` (CPython 3.13.18), `.venv314` (3.14.10, `C:\Python314`), `.venv315` (3.15.2); each lane hosted by its own interpreter.
- Linux: WSL Ubuntu 22.04 (glibc 2.35), Temurin 25.0.5, uv-managed 3.13.18 / 3.14.10 / 3.15.2 in `~/pyjvm-n0/.venv31x`; per-lane caches. No "host version differs" refusal on any lane.

## Counts

| Gate | Windows | Linux |
|---|---|---|
| pytest | 132 passed, **1 failed**, 3 skipped (33:35) | 133 passed, **1 failed**, 2 skipped (23:15) |
| methodsize | 4698 methods, 0 over 8000 bytes | 4698 methods, 0 over 8000 bytes |
| conformance 3.15 | 395 passed, 0 failed, 3 skipped | 393 passed, **2 failed**, 3 skipped |
| conformance 3.14 | 380 passed, 0 failed, 18 skipped | 379 passed, **1 failed**, 18 skipped |
| conformance 3.13 | 371 passed, 0 failed, 27 skipped | 369 passed, **2 failed**, 27 skipped |
| strict lane, probe | not run (deferred to ticket 5, as the plan allows) | not run (deferred to ticket 5) |

## Failing names

### Windows
- pytest: `tests/test_licenses.py::test_other_compiler_and_tool_files_are_plain_gpl`. Cause: `tools/posix_measure.py` and `tools/gen_posix_tables.py` (untracked at B0) lacked the GPL SPDX header. Headers added in the ticket-0 commit; not a runtime failure, so it is **not** part of the known-failing set (see "Rechecks").
- conformance: none on any lane.

### Linux
- pytest: `tests/test_class_cache.py::test_concurrent_processes_do_not_corrupt_the_cache`. Cause: environmental; a concurrent JVM printed `[warning][perf,memops] Cannot use file /tmp/hsperfdata_ubuntu/<pid> because it is locked by another process (errno = 11)` on stdout. Known flaky; counts as baseline.
- conformance 3.15: `time_struct_time_and_strftime`, `zlib_streams`
- conformance 3.14: `time_struct_time_and_strftime`
- conformance 3.13: `threading_imports`, `time_struct_time_and_strftime`

Failure details:
- `time_struct_time_and_strftime` (all Linux lanes): `time.gmtime()` struct prints zone `UTC` where CPython prints `GMT` (`tm_zone` of gmtime on glibc). Same output otherwise.
- `zlib_streams` (3.15 only): a gzip header round trip prints `b'EXTRA' b'EXTRA'` where CPython prints `b'EXTRA' b''` (the second value should be empty).
- `threading_imports` (3.13 only): first line `["KeyError('')"] 7 ...` where CPython prints `[] 8 ...` (a `KeyError('')` raised somewhere during the threading checks, and one fewer item counted).

## Flaky on the E: host (WSL Ubuntu 24.04)
- `signal_basic`: failed on all three Linux lanes in one L-min batch during ticket 2's acceptance (2026-11-21), then passed 9/9 (three runs per lane) on the same tree, and 3/3 lanes in the pre-ticket reference run. The failing output was not kept (`conf_cases.sh` prints only the last three lines). Treat a single failure as flaky; rerun alone, and keep the full output if it fails again.

## Rechecks
- 2026-11-21: `tests/test_licenses.py` rerun on Windows at cf4f8d3 (headers present): 4 passed. The Windows pytest baseline is therefore 133 passed / 0 failed / 3 skipped for comparison purposes.
