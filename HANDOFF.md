# Hand-off notes (cloud session -> local session)

Written 2026-11-13. Branch: `ccr-53990e90-8do8kj`. `origin/main` is still at 1654ee0 and has NOT been updated with anything after it.

## Read first
- `ROADMAP.md` - the main roadmap (phases P1-P6, interop track). 
- `NATIVE_EXTENSIONS_PLAN.md` - Java 25 baseline, Rust `pyjvm` crate, C-API shim (tickets R0-R4, C-xx).
- `README.md`, `JVM_BACKEND.md`, `STDLIB_STATUS.md` (per-module status), `CPYTHON_REGRESSION_PROBE.md`.

## Standing decisions (from the user)
- Backwards compatibility is not a concern (pre-alpha, no users). Python 3.13 is the minimum supported version; 3.15 is the target.
- Java 25 or newer is the target (FFM final). The re-target (R0-01) is on a separate branch `ticket/r0-01-java25` (commit c34c92e) and may not be pushed; its change is small: `JAVA_TARGETS=(25,)`/`DEFAULT_TARGET=25` in `pyjvm315/classfile.py` (class file major 69), `javac --release 25` in `pyjvm315/cli.py` and tests, `--target 25` in `.github/workflows/*.yml`, `tools/resumable.py`, `CPYTHON_REGRESSION_PROBE.md`, `tests/test_stackmap.py` (65 -> 69), README/JVM_BACKEND wording. A smoke conformance run (sys_attrs, tokenize_basic, traceback_basic, self-hosted, 3.15) passed on JDK 25.0.5 (Temurin). Full gates and the benchmark re-baseline on JDK 25 are NOT done.
- No PR unless asked. Push `main` only on explicit instruction and only after the full gates pass. Licensing: GPL-3.0-or-later (+ linking exception draft for runtime/compiler CORE), CPython-derived code stays PSF, CLA to rbroderi. Do not commit compiled stdlib jars or classes.
- No placeholders: an unimplemented feature must raise a clear CPython-style error (and be listed as unsupported), not silently return fake values. The unittest worker added "importable stubs" for `_posixsubprocess`, `fcntl`, `pwd`, `grp` - REVIEW these and replace anything fake with real FFM-backed code or an explicit unsupported error.

## What is merged on this branch
Phase 1-3 work, sys completeness, native `_tokenize`, traceback, class-cache + `PYJVM_BUILD_CACHE` + `tools/resumable.py`, tokenize gap fixes, stdlib jar pipeline + `STDLIB_STATUS.md`, unittest/`test.support` work (P3-05, partly), and the compiler-audit worker's fixes (`__main__` registration, relative imports, `math.sumprod`, `os.terminal_size`, ...). The audit worker's findings table was not written when this was merged.

## NOT verified since the last merges
Full gates have not been run on the merged head: `python3 -m pytest -q`, conformance on both lanes (3.13 and 3.15, `--selfhosted`), the probe, the strict execution lane, benchmarks. Two workers' conflicting `time` calendar implementations were merged by taking the `TimeCalendar.java` from the unittest worker; check `time` conformance cases. Run the gates first.

## Gate commands
```
python3 -m pytest -q                                   # long; per-file via tools/resumable.py pytest
python3 -m pyjvm315.methodsize --runtime              # 0 methods over 8000 bytes
PYTHONPATH=. python3.15 -m pyjvm315.conformance tests/conformance --python python3.15 --selfhosted
PYTHONPATH=. python3.15 -m pyjvm315.conformance tests/conformance --python python3 --selfhosted   # 3.13 lane (use python3)
PYTHONPATH=. python3.15 -m pyjvm315.regression_probe --manifest tools/cpython_probe_3000_manifest.txt \
  --cpython-root <cpython Lib checkout> --baseline tools/cpython_probe_3000_baseline.json \
  --expect-cases 3000 --min-compiles 2918 --fail-on-error
PYTHONPATH=. python3.15 -m pyjvm315.cpython_execution --cpython-root <cpython> \
  --manifest tools/cpython_execution_manifest.txt --target 21 --selfhosted --build-cache DIR   # strict lane (25 after R0-01)
python3 tools/stdlib_status.py all --lane 3.15 --lib <Lib> --work DIR    # stdlib jar + per-module manifest
```
`tools/resumable.py` runs pytest/conformance/strict in small resumable pieces (`--out DIR --jobs N --force --dry-run`). Set `PYJVM_BUILD_CACHE` and `PYJVM_CACHE` to your own dirs; the first cold compile takes 200-280 s. CPython corpus: commit `5b28ebd109f08cfc44a3d6e573e087eaf86319b5` (Lib only).
Needs: Python 3.13 and 3.15, JDK (21 until R0-01 lands, then 25), `cargo` (for later Rust work), a CPython Lib checkout.

## Open items (priority order)
1. Run all gates on the branch head; fix regressions from the merges.
2. Land R0-01 (Java 25) on top, rerun gates, re-baseline benchmarks once (record before/after).
3. Native modules for the stdlib blockers (see `STDLIB_STATUS.md`): `_typing` (51 modules on 3.13), `_socket` (73), `_ctypes` (12), `_posixsubprocess` (8), `_codecs_*` CJK (7 each), `_curses`, `_sqlite3`. Plan: pure Java (`java.nio`, `Charset`) where Java suffices; FFM (`java.lang.foreign`) for libc-shaped modules; Rust only per `NATIVE_EXTENSIONS_PLAN.md`; POSIX-only modules declared unsupported on Windows. Keep per-OS code split (Posix*/Windows*). Use `captureCallState("errno")` for errno.
4. Finish P3-05: `test.support` helpers, runner for real `Lib/test` modules (test_bool/tuple/sort/copy/struct), libregrtest.
5. P2-03 code-point `str` (most invasive; run alone, last).
6. Known sys gaps: `setrecursionlimit` not exact, `f.f_globals is globals()` False, `1/0` has no `__traceback__`, no audit events from the runtime, `cache_tag` None.
7. Roadmap Phases 4-6 and the interop track (I-01..I-06) not started. Native extension tickets R1-R4 not started.
8. A partial stdlib jar containing a package but not all submodules does not fall back to source.

## Environment lessons (cloud container; may not apply locally)
Container restarts ~every 7 min killed background jobs; stale half-built caches caused bogus failures ("no attribute compile_source") - wipe cache dirs and prefer `PYJVM_BUILD_CACHE` (atomic builds). Background JVM jobs must reset SIGINT (`perl -e '$SIG{INT}="DEFAULT"; exec @ARGV'`).
