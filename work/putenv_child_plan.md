# Plan: check that a child process sees `os.putenv` / `os.unsetenv` (HANDOFF open item 12; written by a planning agent, 2026-11-16)

## 1. Goal
A conformance check, on Windows and on Linux, that `os.putenv`, `os.unsetenv` and `os.environ` writes reach the environment a child process inherits, and that `os.putenv` alone does not change `os.environ`. This is the observable reason the functions exist; today only the parent side is tested.

## 2. Current state (file:line)
- `tests/conformance/os_putenv.py`: parent-side behaviour and argument validation only. It ends with the TODO at lines 59-60 ("check that child processes see putenv/unsetenv once subprocess works in this runtime on both OSes").
- Implementation: `PosixFiles.putenv/unsetenv/setEnv` (`runtime/pyjvm315/runtime/PosixFiles.java:260-297`): on nt `WindowsNative.putenv` = UCRT `_wputenv_s` (`WindowsNative.java:332-336`), which updates the CRT environment and the Win32 process block; on POSIX libc `setenv`/`unsetenv` through FFM (`PosixNative.java:33-34, 51-62`).
- Windows process creation is real: `_winapi.CreateProcess` passes a NULL environment block when `env` is None (`WindowsWinApiModule.java:241-244`, `WindowsHandles.java:311`), so the child inherits the live process block.
- `tests/conformance/subprocess_windows.py:38-41` already checks one path on nt: `os.environ["PYJVM_SUBPROC_PUT"] = "inherited"` then `cmd /c echo %PYJVM_SUBPROC_PUT%`, and the same after `del`. Raw `os.putenv`/`os.unsetenv` are not covered.
- POSIX has **no process creation**: `_posixsubprocess.fork_exec` raises NotImplementedError (`StubModules.java:18-22`), `posix.waitpid` is a lambda that always raises ChildProcessError (`PosixFiles.java:41-42`), and there is no `os.pipe`/`dup2`/`posix_spawn`/`system`/`popen` on POSIX (function list in `PosixFiles`/`PosixModule`; `tests/conformance/os_pipe.py:6-7` says the same). So the Linux half of this item is blocked on phase N1 of `native_modules_plan.md`.
- Cost: the first case on a lane that imports `subprocess` with a cold build cache takes 8-13 minutes (HANDOFF "Wave 1 verification"); with the cache warm (any earlier `subprocess_windows`/`asyncio_subprocess_pipes` run on that lane's `bc-conf*`/`c-conf*`) it is an ordinary case. The runner has no per-case timeout (`pyjvm315/conformance.py:30`, `_run`), so a hang in `communicate()` hangs the lane.

## 3. CPython reference behaviour
- `os.putenv(k, v)` changes the C-level environment only; `os.environ` and `os.getenv` do not see it (os.py keeps its own mapping). `os.environ[k] = v` calls `putenv` and updates the mapping; `del os.environ[k]` calls `unsetenv`.
- `subprocess` with `env=None`: POSIX `fork_exec(..., env_list=None)` uses `execv`, so the child gets the C `environ` (raw putenv visible). Windows passes NULL to `CreateProcess`, same effect. With `env=dict(os.environ)` the child gets the mapping, so a raw `putenv` variable is **not** visible. That contrast is the discriminating check.
- Windows: `_wputenv_s(name, "")` removes the variable, so `os.putenv("X", "")` makes it unset in the child; `cmd /c echo [%X%]` prints the literal `[%X%]` for an unset variable. POSIX: an empty value is a set, empty variable.
- Verified here only by reading CPython's sources and by the existing nt case; the exact child output is produced by the reference interpreter at run time, so nothing in the case hard-codes it.

## 4. Design
Decision: **a new case `tests/conformance/os_putenv_child.py`**, not an extension of `os_putenv.py`.
- `os_putenv.py` stays a fast case with no `subprocess` import (it is in the wave-1 quick set); the new case carries the cold-cache cost only on the first run of a lane and shares the warm cache with `subprocess_windows`.
- One file for both OSes, shaped like `subprocess_windows.py`: an unconditional first `print(os.name)`, then per-OS helpers and a shared body. Until POSIX process creation exists the POSIX branch is not in the file (both interpreters print just the first line there, the convention of `os_pipe.py`/`subprocess_windows.py`); it is added in stage 2.
- The child is a system tool, never `sys.executable` (the reference runs `python`, the runtime is a JVM: the children must be the same program on both sides and must start in milliseconds).
  - nt: `[os.environ["ComSpec"], "/d", "/c", "echo [%NAME%]"]`; `/d` skips AutoRun. Unset shows as the literal `[%NAME%]`; normalise in the helper (`b"<unset>"` when the output equals the literal).
  - posix: `["/bin/sh", "-c", 'printf "[%s]" "${NAME-<unset>}"']` (distinguishes unset from empty; no dependence on `printenv`/`env` being installed, `/bin/sh` is absolute so `subprocess` may take either its `posix_spawn` or its `fork_exec` path).
- A single helper `child(name, env=None) -> bytes` using `subprocess.run(..., capture_output=True, env=env).stdout.strip()`; print the normalised value. ASCII names and values only on nt (cmd's output code page would otherwise enter the comparison); one non-ASCII UTF-8 value on posix (bytes straight through `printf`).
- Variable names carry the pid (`PYJVM_PEC_<pid>_A`) but are never printed; values are fixed strings. Clean up with `unsetenv` in a `finally` so a failing run does not leak into the runner's later cases (the runner's children are separate processes anyway).

Checks (each prints one line; identical code on both OSes unless marked):
1. Baseline: `child(A)` is unset.
2. `os.putenv(A, "raw")`: `child(A)` is `raw`; `os.getenv(A)` is None; `A in os.environ` False.
3. Explicit mapping: `child(A, env=dict(os.environ))` is unset (the mapping does not contain it). On nt the dict must keep `SystemRoot`/`ComSpec`, which `dict(os.environ)` does.
4. Overwrite: `os.putenv(A, "raw2")`: child sees `raw2`.
5. `os.unsetenv(A)`: child unset.
6. `os.environ[B] = "map"`: child sees `map`; `os.getenv(B)` is `map`. `os.environ[B] = "map2"`: `map2`. `del os.environ[B]`: unset.
7. Mapping and raw disagree: `os.environ[C] = "one"`, `os.putenv(C, "two")`: `os.environ[C]` still `one`, `child(C)` is `two`, `child(C, env=dict(os.environ))` is `one`.
8. `os.environ.pop`, `setdefault`, `update` each followed by one `child` call (they route through `__setitem__`/`__delitem__`).
9. Empty value: `os.putenv(D, "")`: nt: unset; posix: set and empty (printed as `[]`). The per-OS expectation is not coded: the line prints what the child reports.
10. posix only (stage 2): bytes API `os.putenv(b"...", b"v")`, a non-ASCII value, a value containing `=` and spaces; `os.unsetenv` of a name that was only in the mapping.
11. nt only: case-insensitive names (`os.putenv("pyjvm_pec_x", "v")` then `%PYJVM_PEC_X%`), a value with spaces and `&` is avoided (cmd parsing); a 2000-character value (length printed, not the value).
12. Two children in a row after one change (the block is inherited each time, not consumed).

Optional cheap smoke inside `os_putenv.py` itself (nt only, no `subprocess` import, costs nothing on a cold cache): `_winapi.CreateProcess(None, 'cmd /d /c if "%NAME%"=="raw" (exit 7) else (exit 3)', None, None, False, 0, None, None, None)`, `WaitForSingleObject`, `GetExitCodeProcess`, `CloseHandle` x2; prints the exit code. Recommended only if the owner wants the quick set to cover the child path; otherwise leave `os_putenv.py` untouched apart from replacing its TODO with a pointer to the new case.

## 5. Dependencies for the POSIX half (must be designed into `native_modules_plan.md` phase N1)
- A real `_posixsubprocess.fork_exec` (or `os.posix_spawn`) and `os.pipe`/`waitpid`.
- **The spawn must hand the child the libc `environ`, not the JVM's view.** `System.getenv()`/`ProcessBuilder.environment()` are a snapshot taken at JVM start and never see `setenv`. An implementation of `fork_exec(env_list=None)` on top of `ProcessBuilder`, or one that builds the child's envp from Java's map, would pass every other subprocess test and fail exactly this case. With `posix_spawn` through FFM: pass the current value of the C global `environ` (`Linker.nativeLinker().defaultLookup().find("environ")`, read the pointer at call time) as `envp` when `env_list` is None.
- `/bin/sh` child with captured stdout needs kernel pipe descriptors that the child can inherit (N0/N1 of that plan).

## 6. Steps
Stage 1 (now, Windows; Linux prints one line):
1. Write `os_putenv_child.py` with the nt branch (checks 1-9, 11, 12).
2. Run under each lane's CPython directly first (`.venv315\Scripts\python.exe tests\conformance\os_putenv_child.py`, seconds) to see the expected output is stable across two runs and across 3.13/3.14/3.15.
3. Run the case on one lane with a warm cache: `pyjvm-work\run_cases.ps1 -CaseList subprocess_windows,os_putenv_child -Lanes 315` (the first case warms the cache and proves subprocess still works; if `bc-conf` already holds the subprocess classes this is minutes). Then lanes 314 and 313, one at a time.
4. Replace the TODO in `os_putenv.py:59-60` with a one-line pointer; HANDOFF item 12: "Windows child check done (os_putenv_child); POSIX half waits for `_posixsubprocess`".
Stage 2 (after native_modules N1 lands):
5. Add the posix branch (checks 1-10, 12) and run it in WSL on the three lanes (`~/pyjvm-work/conf_cases.sh os_putenv_child`; HANDOFF "WSL": write scripts as files, not through PowerShell quoting; the per-lane host change of HANDOFF item 2 is needed first).
6. Rerun stage 1's Windows runs (the file changed).

## 7. Verification
- The case passes on Windows 3.13/3.14/3.15 (stage 1) and on Linux x3 (stage 2), and `os_putenv`, `subprocess_windows`, `os_platform` still pass.
- Negative control, once, by hand: temporarily make nt `putenv` a no-op (or point the helper at a different variable) and confirm the case fails; a child check that cannot fail is the risk with echo-based tests.
- Time budget: cold first run 8-13 min per lane; warm under a minute plus JVM start. Do not run alongside agents or other gates (memory); expect the occasional build-cache WinError 5 rename failure on Windows and rerun.

## 8. Risks and open questions
1. Linux half is blocked; nothing in pyJVM can start a process on POSIX today. If the owner wants a Linux check before N1, the only route is FFM `getenv` from a test-only hook, which the no-placeholder policy and the "observable behaviour" goal both argue against. Recommendation: wait for N1.
2. `cmd.exe` quirks: `echo [%X%]` with an unset variable prints the literal (handled); variables whose value contains `&|<>^%` would be re-parsed (avoided); `ComSpec` could point at another shell on an unusual machine (the existing subprocess case has the same assumption).
3. A hang (child waiting, pipe not closed) blocks the lane because the runner has no timeout; pass `timeout=60` to every `subprocess.run` so a failure is an exception in the case instead. `subprocess.TimeoutExpired` output then differs from the reference and the case fails, which is the wanted result.
4. Windows environment block is process-global: the case must not run concurrently with another case in the same JVM (it does not: one JVM per case).
5. The putenv audit events (`3fb3251`) fire for each call; nothing here installs a hook, no interaction.
6. Size: about 70 lines of Python for stage 1, 25 more for stage 2. No runtime change is expected on Windows; if a check fails there, the likely spots are `WindowsNative.putenv` (empty value / case folding) and `WindowsWinApiModule.environment`.
