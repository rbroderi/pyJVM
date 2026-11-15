# Hand-off notes

Updated 2026-11-13 by the local Windows session, which took the branch over from the cloud session (that session has stopped working). Branch: `ccr-53990e90-8do8kj`, checked out at `E:\pyjvm`. `origin/main` is still at 1654ee0.

## State of the working tree (read this first)
- The checkout is at origin head **4da983b** (both cloud workers merged: unittest/`test.support`, compiler audit, `_typing`, `_socket`/`resource` shells) **plus a large UNCOMMITTED change set** described below. Nothing has been committed or pushed by the local session. Commit/push only when the user asks.
- `ticket/r0-01-java25` (c34c92e) on origin is superseded by the uncommitted R0-01 changes here; do not merge it.

### Uncommitted change set
1. **R0-01, Java 25 re-target.** `JAVA_TARGETS=(25,)`/`DEFAULT_TARGET=25` (class file major 69), `javac --release 25` (cli, tests, `tools/ast.sh`, `tools/tok.sh`), `--target 25` and `java-version: '25'` in CI, `tools/resumable.py`, docs (README, JVM_BACKEND, CPYTHON_REGRESSION_PROBE, ROADMAP). Every runtime JVM launch now passes `--enable-native-access=ALL-UNNAMED` (conformance, selfhost, stdlib_status, run_cpython_test, benchmarks, tests): without it Java 25 prints a restricted-method warning on stderr once FFM is used, which breaks output comparisons.
2. **Windows support** (the user's choice over running the gates in WSL):
   - `runtime/.../WindowsNtModule.java`: `nt`, sharing posix's java.nio core (`PosixModule.build`), with Windows `O_*` values mapped onto IoFd's flags (`O_TEMPORARY` -> new `IoFd.DELETE_ON_CLOSE`), str-keyed `environ`, `_create_environ`, chmod/lchmod/fchmod as the read-only attribute, `stat`/`lstat`/`fstat` matching CPython 3.12+ on Windows (64-bit `st_dev` and 128-bit `st_ino` from `FILE_ID_INFO`, change time as `st_ctime`, `st_birthtime*`, `st_file_attributes`, `st_reparse_tag`, `.exe/.bat/.cmd/.com` exec bits), `_path_splitroot` (importlib needs it), `_getfullpathname`, `_getfinalpathname`, `_findfirstfile`, `_getdiskusage`, `get_terminal_size`, UCRT `strerror` table.
   - `WindowsNative.java`: the FFM layer (kernel32/advapi32/kernelbase), `GetLastError` via `captureCallState`, `winError()` builds `OSError` with `winerror` and FormatMessage text; winerror->errno and errno->subclass tables generated from CPython (3.13 and 3.15 agree).
   - `WindowsRegModule.java`: `winreg`, real (read, write, enum, delete, `DeleteTree` on 3.15 only, Load/Save, reflection), `PyHKEY` objects, Py2Reg/Reg2Py conversions.
   - `ImportSupport`: `NATIVE_NAMES` is per OS (POSIX-only: posix, `_posixsubprocess`, fcntl, pwd, grp, resource; Windows-only: nt, winreg), `OS_MODULE` drives the boot order, `sys.path[0]` handles `\` paths, `winerror` attribute (Windows only). `PyExcArgs` prints `[WinError N]`. `ErrnoModule` has the Windows errno table. `PosixFiles.scandir` joins with `\` on Windows.
   - Tools: `tools/resumable.py` no `preexec_fn` on Windows; `pyjvm315/selfhost.py` uses `os.pathsep` and writes the jobs file with LF.
3. **Parser fix (all platforms):** `Parser` translates `\r\n` and lone `\r` to `\n` before tokenizing, like CPython's compile()/ast.parse (the `_tokenize` path keeps CRLF, also like CPython). Found because git on Windows checks files out with CRLF.
4. **New conformance cases:** `tests/conformance/os_platform.py` (branches on `os.name`, so it runs on Linux CI too; on Windows it creates and deletes `HKCU\Software\pyjvm-conformance-<pid>`), `tests/conformance/compile_crlf_source.py`.
5. Test hygiene: `test_conformance_units_groups_of_four` clears `PYJVM_BUILD_CACHE`; `test_children_get_default_sigint` is Linux-only.

### What has been verified (Windows 11, Temurin 25.0.5, uv Python 3.13.13 and 3.15.2)
- After the merge: runtime compiles with `javac --release 25`; `test_stackmap`, `test_parser`, `test_resumable_runners`: 38 passed, 1 skipped; methodsize 4079 methods, 0 over 8000 bytes; conformance `os_platform`, `compile_crlf_source`, `native_stdlib_modules` pass on both lanes.
- Before the merge: `test_import_runtime` 4/4 and `test_selfhost` 2/2 passed.

### NOT verified
- **Full pytest** on this tree. The one full run (before the merge) gave 40 failed / 8 errors, dominated by JVMs crashing out of native memory (see Environment). It must be rerun with free memory before judging.
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

## Local environment (E:\pyjvm, Windows)
- Toolchains: Temurin JDK 25.0.5 (`JAVA_HOME` set machine-wide), uv 0.13.8 with Python 3.13.13 and 3.15.2, Rust/cargo 1.100 with VS 2022 Build Tools (C++).
- Shells opened before those installs may have a stale PATH; reload it: `$env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')`.
- The project is not installable (setuptools flat-layout error: `runtime` and `pyjvm315`), so always use `uv run --no-project` from the repo root.
- CPython corpus: `E:\cpython` (sparse checkout, `Lib` only) at `5b28ebd109f08cfc44a3d6e573e087eaf86319b5`; all 60 files the probe/strict manifests use are present.
- Caches used so far: `E:\pyjvm-work\bc25`, `E:\pyjvm-work\c25` (pytest), `E:\pyjvm-work\bc-conf`, `E:\pyjvm-work\c-conf` (conformance). Scratch scripts that generated the Windows tables are in `E:\pyjvm-work\gen_*.py`.
- **Memory:** on 2026-11-12/13 the machine was out of commit (99.7 of 100.3 GB): the WSL VM held ~32 GB and the kernel nonpaged pool ~26 GB, 23 GB of it pool tag `NtFC` (ntfs.sys, Create.c; ~66M live 352-byte allocations, a leak; cause not yet identified; reboot reclaims it). Tests that start many JVMs at once (e.g. `test_class_cache` concurrency) then crash with "insufficient memory" / "paging file is too small" and leave `hs_err_pid*.log` / `replay_pid*.log` files in the repo root and test dirs; delete them. Check free memory before a full run; the user needs WSL running, so do not shut it down without asking.

## Gate commands (PowerShell, from E:\pyjvm)
```
$env:PYJVM_BUILD_CACHE='E:\pyjvm-work\bc25'; $env:PYJVM_CACHE='E:\pyjvm-work\c25'
uv run --no-project --python 3.13 --with pytest python -m pytest -q -p no:cacheprovider        # ~35 min on Windows; per-file: tools/resumable.py pytest
uv run --no-project --python 3.13 python -m pyjvm315.methodsize --runtime                      # 0 methods over 8000 bytes
$env:PYTHONPATH='E:\pyjvm'
uv run --no-project --python 3.15 python -m pyjvm315.conformance tests/conformance --python (uv python find --no-project 3.15) --selfhosted
uv run --no-project --python 3.15 python -m pyjvm315.conformance tests/conformance --python (uv python find --no-project 3.13) --selfhosted   # 3.13 lane
uv run --no-project --python 3.15 python -m pyjvm315.regression_probe --manifest tools/cpython_probe_3000_manifest.txt --cpython-root E:\cpython --baseline tools/cpython_probe_3000_baseline.json --expect-cases 3000 --min-compiles 2918 --fail-on-error
uv run --no-project --python 3.15 python -m pyjvm315.cpython_execution --cpython-root E:\cpython --manifest tools/cpython_execution_manifest.txt --target 25 --selfhosted --build-cache E:\pyjvm-work\strict   # strict lane; prefer tools/resumable.py strict
uv run --no-project --python 3.15 python tools/stdlib_status.py all --lane 3.15 --lib <Lib> --work <dir>   # stdlib jar + per-module manifest
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
