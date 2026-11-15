# Hand-off notes

Updated 2026-11-14. Branch: `ccr-53990e90-8do8kj`, head **46f8e0f** on origin. `origin/main` is still at 1654ee0. The branch is now worked from a fresh clone at `C:\Users\richa\workspace_local\pyjvm` (see Local environment). The earlier local session worked at `E:\pyjvm`; that drive is not mounted here.

## State of the branch (read this first)
- **46f8e0f** "Windows support (nt, winreg via FFM) and Java 25 re-target (R0-01)" sits on top of 4da983b (both cloud workers merged: unittest/`test.support`, compiler audit, `_typing`, `_socket`/`resource` shells). It holds the whole change set described below; nothing is left uncommitted.
- `ticket/r0-01-java25` (c34c92e) on origin is superseded by 46f8e0f; do not merge it.
- Commit/push only when the user asks.

### What 46f8e0f contains
1. **R0-01, Java 25 re-target.** `JAVA_TARGETS=(25,)`/`DEFAULT_TARGET=25` (class file major 69), `javac --release 25` (cli, tests, `tools/ast.sh`, `tools/tok.sh`), `--target 25` and `java-version: '25'` in CI, `tools/resumable.py`, docs (README, JVM_BACKEND, CPYTHON_REGRESSION_PROBE, ROADMAP). Every runtime JVM launch now passes `--enable-native-access=ALL-UNNAMED` (conformance, selfhost, stdlib_status, run_cpython_test, benchmarks, tests): without it Java 25 prints a restricted-method warning on stderr once FFM is used, which breaks output comparisons.
2. **Windows support** (the user's choice over running the gates in WSL):
   - `runtime/.../WindowsNtModule.java`: `nt`, sharing posix's java.nio core (`PosixModule.build`), with Windows `O_*` values mapped onto IoFd's flags (`O_TEMPORARY` -> new `IoFd.DELETE_ON_CLOSE`), str-keyed `environ`, `_create_environ`, chmod/lchmod/fchmod as the read-only attribute, `stat`/`lstat`/`fstat` matching CPython 3.12+ on Windows (64-bit `st_dev` and 128-bit `st_ino` from `FILE_ID_INFO`, change time as `st_ctime`, `st_birthtime*`, `st_file_attributes`, `st_reparse_tag`, `.exe/.bat/.cmd/.com` exec bits), `_path_splitroot` (importlib needs it), `_getfullpathname`, `_getfinalpathname`, `_findfirstfile`, `_getdiskusage`, `get_terminal_size`, UCRT `strerror` table.
   - `WindowsNative.java`: the FFM layer (kernel32/advapi32/kernelbase), `GetLastError` via `captureCallState`, `winError()` builds `OSError` with `winerror` and FormatMessage text; winerror->errno and errno->subclass tables generated from CPython (3.13 and 3.15 agree).
   - `WindowsRegModule.java`: `winreg`, real (read, write, enum, delete, `DeleteTree` on 3.15 only, Load/Save, reflection), `PyHKEY` objects, Py2Reg/Reg2Py conversions.
   - `ImportSupport`: `NATIVE_NAMES` is per OS (POSIX-only: posix, `_posixsubprocess`, fcntl, pwd, grp, resource; Windows-only: nt, winreg), `OS_MODULE` drives the boot order, `sys.path[0]` handles `\` paths, `winerror` attribute (Windows only). `PyExcArgs` prints `[WinError N]`. `ErrnoModule` has the Windows errno table. `PosixFiles.scandir` joins with `\` on Windows.
   - Tools: `tools/resumable.py` no `preexec_fn` on Windows; `pyjvm315/selfhost.py` uses `os.pathsep` and writes the jobs file with LF.
   - The Windows tables (errno values, winerror mapping, strerror texts, winreg constants, stat fields and `O_*` flags) were read out of Windows CPython by scratch scripts that were not committed (`gen_errno.py`, `gen_winerr*.py`, `gen_strerror.py`, `gen_winreg.py`, `probe_stat.py` in the old `E:\pyjvm-work`). Their output is already in the Java code; they are only needed to regenerate the tables against another CPython.
3. **Parser fix (all platforms):** `Parser` translates `\r\n` and lone `\r` to `\n` before tokenizing, like CPython's compile()/ast.parse (the `_tokenize` path keeps CRLF, also like CPython). Found because git on Windows checks files out with CRLF.
4. **New conformance cases:** `tests/conformance/os_platform.py` (branches on `os.name`, so it runs on Linux CI too; on Windows it creates and deletes `HKCU\Software\pyjvm-conformance-<pid>`), `tests/conformance/compile_crlf_source.py`.
5. Test hygiene: `test_conformance_units_groups_of_four` clears `PYJVM_BUILD_CACHE`; `test_children_get_default_sigint` is Linux-only.

### What has been verified (on the earlier E: machine: Windows 11, Temurin 25.0.5, uv Python 3.13.13 and 3.15.2)
- After the merge: runtime compiles with `javac --release 25`; `test_stackmap`, `test_parser`, `test_resumable_runners`: 38 passed, 1 skipped; methodsize 4079 methods, 0 over 8000 bytes; conformance `os_platform`, `compile_crlf_source`, `native_stdlib_modules` pass on both lanes.
- Before the merge: `test_import_runtime` 4/4 and `test_selfhost` 2/2 passed.
- **Fresh clone, 2026-11-15 (`.venv313`, CPython 3.13.18; uv's 3.13.18/3.15.2 on PATH for the lane tests):** full pytest on 46f8e0f gave 17 failed / 114 passed / 3 skipped (29 min), no out-of-memory crashes. All 17 were Windows issues in tests/tools, fixed uncommitted:
  - `tests/test_class_cache.py` (13): printed `__file__.rsplit("/")`; Windows paths use `\`. Now `os.path.basename`. 13/13 pass.
  - `tools/stdlib_status.py` compile stage: `threading.stack_size(512 MiB)`; CPython on Windows needs < 256 MiB, so 255 MiB on `nt`.
  - `tests/test_stdlib_jar.py` 3.15 probe: the first `json` imports compile re/enum/... into an empty JVM cache; with `-j 2` that took ~469 s on 3.15 (3.13: 234.5 s, just under) against the 240 s default (reproduced; thread dumps show the self-hosted compiler working, not a hang). The test now passes `--timeout 600`; `stdlib_status.py` also kept an explicit `--timeout` only for the compile stage (reset to None after it) and now applies it to every stage. 8/8 pass.
  - The full suite has not been rerun end to end after these fixes (only the two files).
- Cold self-hosted compiles are slow on this machine (~235 s for `json`'s dependencies on one JVM); worth a look when benchmarks are re-baselined.

### NOT verified
- **Full pytest** end to end after the fixes above (the two affected files pass individually).
- Full conformance (both lanes), the probe, the strict lane, benchmarks (Java 25 re-baseline not done). Also unverified from the cloud side: `time` conformance after the `TimeCalendar.java` merge, `unittest.main()`/discovery.
- The Windows changes have not been run on Linux. They are guarded by `ImportSupport.WINDOWS`, but run the Linux gates (CI or WSL) before pushing.

## Read first
- `ROADMAP.md` (phases P1-P6, interop track), `NATIVE_EXTENSIONS_PLAN.md` (Java 25 baseline, Rust crate, C-API shim; tickets R0-R4, C-xx).
- `README.md`, `JVM_BACKEND.md` (includes the compiler-audit "Stdlib import-failure triage" table), `STDLIB_STATUS.md` (counts are stale; regenerate), `CPYTHON_REGRESSION_PROBE.md`.

## Standing decisions (from the user)
- Backwards compatibility is not a concern (pre-alpha, no users). Python 3.13 is the minimum supported version; 3.15 is the target.
- Java 25 or newer is the target (FFM final).
- No PR unless asked. Push `main` only on explicit instruction and only after the full gates pass. Do not commit or push the branch without being asked. Licensing: GPL-3.0-or-later (+ linking exception draft for runtime/compiler CORE), CPython-derived code stays PSF, CLA to rbroderi. Do not commit compiled stdlib jars or classes.
- No placeholders: an unimplemented feature must raise a clear CPython-style error (and be listed as unsupported), not silently return fake values. Prefer leaving a function absent (so `hasattr` probes and os.py's `_exists` see it as missing) to defining one that pretends.
- Use **uv** for all Python (interpreters, runs). On this machine `python3` is the Microsoft Store placeholder; never call it.

## Local environment (C:\Users\richa\workspace_local\pyjvm, Windows 11)
- Repo: fresh clone of `https://github.com/rbroderi/pyJVM` at `C:\Users\richa\workspace_local\pyjvm`. `core.autocrlf` is `true`, so files check out with CRLF (the parser fix above handles that).
- CPython corpus: `C:\Users\richa\workspace_local\cpython` (blobless sparse clone, `Lib` only) at `5b28ebd109f08cfc44a3d6e573e087eaf86319b5`.
- Scratch/caches: use `C:\Users\richa\workspace_local\pyjvm-work` (outside the repo; nothing there needs keeping). On the earlier machine the pytest caches (`bc25`, `c25`) stayed empty: the tests build in their own temporary directories, so setting `PYJVM_BUILD_CACHE`/`PYJVM_CACHE` for pytest is optional. Only the conformance runs used their cache (`bc-conf`, `c-conf`).
- Toolchains:
  - JDK: Temurin 25.0.5 (`C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`) is first on PATH for `java`/`javac`. **`JAVA_HOME` (machine-wide) points to Amazon Corretto 21.0.13**, which cannot run class file major 69. The project does not read `JAVA_HOME` today; if a tool starts to, repoint it to the Temurin 25 JDK.
  - uv 0.13.8 (installed with pip under `C:\Python312`, so upgrade it with `C:\Python312\python.exe -m pip install --user --upgrade uv`; `uv self update` does not work). Older uv releases install 3.15.0rc1 for `3.15`.
  - Python: one local venv per lane in the repo root, built from **uv-managed** interpreters (not the system ones in `C:\Python313`/`C:\Python315`): `.venv313` (CPython 3.13.18) and `.venv315` (CPython 3.15.2), each with pytest. uv puts a `*` `.gitignore` inside each venv, so git ignores them. Recreate with `uv python install --upgrade 3.13 3.15`, then `uv venv --managed-python --python 3.13 .venv313` / `--python 3.15 .venv315` and `uv pip install --python .venv313 pytest` (same for `.venv315`).
  - Rust/cargo 1.91.1.
- Shells opened before toolchain installs may have a stale PATH; reload it: `$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`.
- The project is not installable (setuptools flat-layout error: `runtime` and `pyjvm315`), so run the venv interpreters from the repo root with `PYTHONPATH` set to the repo (or `uv run --no-project --python .venv313 ...`).
- **Memory:** on 2026-11-12/13 the earlier machine was out of commit (99.7 of 100.3 GB): the WSL VM held ~32 GB and the kernel nonpaged pool ~26 GB, 23 GB of it pool tag `NtFC` (ntfs.sys, Create.c; ~66M live 352-byte allocations, a leak; cause not yet identified; reboot reclaims it). Tests that start many JVMs at once (e.g. `test_class_cache` concurrency) then crash with "insufficient memory" / "paging file is too small" and leave `hs_err_pid*.log` / `replay_pid*.log` files in the repo root and test dirs; delete them. On 2026-11-14 this machine had 39 of 73 GB commit free and an 8 GB nonpaged pool. Check free memory before a full run; the user needs WSL running, so do not shut it down without asking.

## Gate commands (PowerShell, from C:\Users\richa\workspace_local\pyjvm)
```
$env:PYTHONPATH='C:\Users\richa\workspace_local\pyjvm'
$env:Path = "$HOME\.local\bin;" + $env:Path   # tests pick CPython via shutil.which('python3.13'/'python3.15'); put uv's shims ahead of chocolatey's
$py13='C:\Users\richa\workspace_local\pyjvm\.venv313\Scripts\python.exe'; $py15='C:\Users\richa\workspace_local\pyjvm\.venv315\Scripts\python.exe'
& $py13 -m pytest -q -p no:cacheprovider                                                       # ~35 min on Windows; per-file: tools/resumable.py pytest
& $py13 -m pyjvm315.methodsize --runtime                                                       # 0 methods over 8000 bytes
$env:PYJVM_BUILD_CACHE='C:\Users\richa\workspace_local\pyjvm-work\bc-conf'; $env:PYJVM_CACHE='C:\Users\richa\workspace_local\pyjvm-work\c-conf'
& $py15 -m pyjvm315.conformance tests/conformance --python $py15 --selfhosted
& $py15 -m pyjvm315.conformance tests/conformance --python $py13 --selfhosted                  # 3.13 lane
& $py15 -m pyjvm315.regression_probe --manifest tools/cpython_probe_3000_manifest.txt --cpython-root C:\Users\richa\workspace_local\cpython --baseline tools/cpython_probe_3000_baseline.json --expect-cases 3000 --min-compiles 2918 --fail-on-error
& $py15 -m pyjvm315.cpython_execution --cpython-root C:\Users\richa\workspace_local\cpython --manifest tools/cpython_execution_manifest.txt --target 25 --selfhosted --build-cache C:\Users\richa\workspace_local\pyjvm-work\strict   # strict lane; prefer tools/resumable.py strict
& $py15 tools/stdlib_status.py all --lane 3.15 --lib <Lib> --work <dir>                        # stdlib jar + per-module manifest
```
`tools/resumable.py` runs pytest/conformance/strict in small resumable pieces (`--out DIR --jobs N --force --dry-run`). The first cold compile takes 200-280 s.

## Open items (priority order)
1. Rerun full pytest with free memory; separate real failures from out-of-memory crashes and fix the real ones.
2. Conformance on both lanes, the probe and the strict lane (target 25) on this tree; check `time` cases.
3. Re-baseline benchmarks once on Java 25 (record before/after).
4. Docs: regenerate `STDLIB_STATUS.md`; add a Windows section listing the gaps below; note `--enable-native-access` in JVM_BACKEND.
5. Native modules (see `STDLIB_STATUS.md`): `_socket`, `_posixsubprocess`, `resource`, `fcntl`, `pwd`, `grp` are import-only shells that raise on use (and `pwd`/`grp` return empty lists from `getpwall`/`getgrall`, which is a fake value) - replace with real implementations (FFM for libc-shaped modules; POSIX-only modules stay absent on Windows); review `_typing` (built from embedded Python source, `TypingSource.java`); then `_ctypes`, `_codecs_*` CJK, `_curses`, `_sqlite3`. Plan: pure Java where Java suffices, FFM otherwise, Rust only per `NATIVE_EXTENSIONS_PLAN.md`; keep per-OS code split (Posix*/Windows*); `captureCallState("errno")` / `("GetLastError")`.
6. Finish P3-05: `unittest.main()` and discovery, `test.support` helpers, libregrtest; CPython test modules test_bool/tuple/sort/copy/struct currently match 141/220 (46 errors, 16 fails, 9 skips) via `tools/run_cpython_test.py`.
7. P2-03 code-point `str` (most invasive; run alone, last).
8. Known sys gaps: `setrecursionlimit` not exact, `f.f_globals is globals()` False, `1/0` has no `__traceback__`, no audit events from the runtime, `cache_tag` None.
9. Roadmap Phases 4-6 and the interop track (I-01..I-06) not started. Native extension tickets R1-R4 not started.
10. A partial stdlib jar containing a package but not all submodules does not fall back to source.
11. `OSError(errno, ...)` constructed from Python is not mapped to its subclass (`OSError(2, 'x')` should be `FileNotFoundError`); the runtime has no errno -> subclass table at construction. Affects every platform.
12. Pre-existing posix placeholders: `fchmod`, `putenv`, `unsetenv` return None without acting; `ast.get_source_segment` is missing.

### Known Windows gaps
- java.nio-backed `os` functions other than `stat` report errors errno-style (`[Errno 2] ...`) where CPython on Windows reports `[WinError N] ...`.
- `O_TEXT` descriptors do binary I/O (no CRLF translation); `os.open` without `O_BINARY` is binary here.
- Absent like in posix: `spawnv`/`execv`/`system`/`startfile`, `pipe`/`dup2`, DLL directories, `listdrives`/`listvolumes`/`listmounts`, the ntpath accelerators (`_path_normpath`, `_path_splitroot_ex`, `_path_is*`, `_getvolumepathname`; ntpath falls back to Python). `_winapi` and `msvcrt` do not exist yet, so `subprocess` cannot import on Windows.
- `os.environ` writes do not reach the process environment (`putenv` is a no-op, as on posix).

## Environment lessons (cloud container; may not apply locally)
Container restarts ~every 7 min killed background jobs; stale half-built caches caused bogus failures ("no attribute compile_source") - wipe cache dirs and prefer `PYJVM_BUILD_CACHE` (atomic builds). Background JVM jobs must reset SIGINT (`perl -e '$SIG{INT}="DEFAULT"; exec @ARGV'`).
