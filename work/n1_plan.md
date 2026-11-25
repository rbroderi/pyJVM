# N1 plan: processes on POSIX (`posix_spawn`, `_posixsubprocess`, wait/kill, sysconf)

Written 2026-11-24 by a planning agent. Parent: `native_modules_plan.md` (phase N1, decisions D2, D4, D5, "Decisions (2026-11-16)" items 2 and 3, section 4 "Order and dependencies") and `putenv_child_plan.md` (stage 2). Builds on N0 (`n0_plan.md`: kernel descriptors, `PosixNative`/`PosixFds`/`PosixOsFds`/`PosixSelect`, the D5 rule, `adopt`/`forget`/`gen`). Target: Linux x86-64 glibc only. Windows behaviour must not change.

Executors: one implementer agent per ticket, of the tier named on the ticket's "Implementer tier" line (Sonnet by default; Opus where the line says so; Haiku only for fully mechanical work; never Fable). After the implementer reports, a **Haiku agent reruns the ticket's acceptance tests and the mechanical checks** (W-min/L-min/P-min, methodsize, the grep/diff checks of section 4 items 1, 2 and 11) and hands the logs to the reviewer. Then a fresh Opus agent reviews the ticket's diff (that ticket's review checklist plus section 4) before the next ticket starts. Every design choice is made here. If an implementer finds a fact that contradicts this plan, they stop and report; they do not pick an alternative.

## 0. How this plan was checked (and what was not)

Measured for this plan on 2026-11-24, with **CPython only and C only** (no pyJVM, no JVM), in WSL Ubuntu 24.04, glibc 2.39, kernel 6.6.87.2, x86_64, as uid 1000, on the uv-managed interpreters `~/pyjvm-n0/.venv313` (3.13.18), `.venv314` (3.14.10), `.venv315` (3.15.2):
- struct sizes, offsets and constants of section 3.2/3.3 with a C program (`gcc`; `E:\pyjvm-work\n1\layout.c`), libc symbols and their versions with `nm -D`;
- the `posix_spawn` behaviours of 3.5 with a C program (`spawn.c`): error returns, no child left after a failed spawn, chdir actions, `adddup2(fd, fd)`, `addclosefrom_np`, attributes, `pidfd_open`, `environ` after `setenv`;
- every CPython behaviour quoted as "measured" (3.4, 3.6, 3.7 and the tickets): scripts `m1.py` (surfaces, sysconf/confstr, W* macros, wait/kill), `m2.py` (`os.posix_spawn[p]`, `fork_exec` called directly, child state), `m3.py` (audit events, `Popen` bookkeeping), `m4.py` (check order, lane texts); outputs `m{1,2,3,4}-31{3,4,5}.txt`, diffed across the three lanes;
- the nine draft conformance cases in `E:\pyjvm-work\n1\cases\` were run twice under each of the three interpreters: stable, and identical across lanes except where 3.8 says so. Their expected outputs are beside them (`<case>-31x-1.out`).

**Not verified** (flagged again where used):
- U1. **No Java/FFM code was run** (owner's instruction: the JVM run lock was in use). Every JVM-side statement is from the Java 25 documentation, the existing `Posix*` classes, or HotSpot/JDK knowledge: FFM `posix_spawn` from a Java thread; HotSpot installs *handlers* (not `SIG_IGN`) for SIGPIPE/SIGXFSZ, so a child gets them as default; Java threads have SIGQUIT blocked; the JDK's process reaper waits only on pids `ProcessImpl` started (the Java half was read in `~/jdk-25/lib/src.zip`: `ProcessHandleImpl.completion(pid, shouldReap)` starts one "process reaper" thread **per pid**; the native `waitForProcessExit0` being `waitpid(pid)`/`waitid(P_PID, pid, WNOWAIT)` is from memory); JDK- and HotSpot-opened descriptors are close-on-exec; HotSpot raises the soft `RLIMIT_NOFILE` to the hard limit, which children inherit. Ticket 1 (spike S2) measures all of them.
- U2. Only glibc 2.39 was measured; the other gate host (HANDOFF "WSL": Ubuntu 22.04, glibc 2.35) was not. Symbols needed exist since glibc 2.34 or earlier (3.1); `pidfd_open` has a libc wrapper only since 2.36, so this plan uses `syscall(434, ...)`. Ticket 0 reruns `layout.c`/`spawn.c` on the 2.35 host if that host is used for gates.
- U3. Reference-interpreter build facts: the uv-managed (python-build-standalone) CPythons have **no `os.POSIX_SPAWN_CLOSEFROM`**, raise `NotImplementedError` for `posix_spawn(setsid=True)`, and lack `os.PIDFD_NONBLOCK` and `os.EX_NOTFOUND`, on all three lanes. A distribution-built CPython differs. This plan matches the reference interpreters the gates compare against (owner question Q3).
- U4. The order of operations inside CPython's forked child (`_posixsubprocess.c: child_exec`) and inside glibc's `__spawni_child` is from reading those sources, not measured; the observable consequences that this plan relies on are measured (chdir happens before exec; a bad cwd wins over a missing program; a bad stdio descriptor is reported as `noexec`).
- U5. `os.kill`/`os.killpg` with pid 0, negative pids, pid -1 or pgid 1 were **not** exercised with a real signal and must not be (2.3). Their behaviour when the JVM itself is among the targets is unmeasured.
- U6. The N1 baseline is N0's final gate result (N0 ticket 13), which did not exist when this plan was written. Ticket 0 records it.
- U7. `sys.executable` is the string `"pyjvm"` under pyJVM (`ImportSupport.java:496`), not a runnable program. CPython's own tests that start `sys.executable` cannot run; ticket 11 classifies them.
- U8. Whether `sun.misc.Signal` accepts `CHLD` (so a Python `SIGCHLD` handler sees a child's exit) is unmeasured; it is in the spike and deliberately in no conformance case.
- U9. The line count (about 1,150 new Java lines against the parent plan's 700) is an estimate.

**Incident while measuring (recorded so that nobody repeats it):** the first version of `m1.py` called `os.killpg(1, 15)`. glibc's `killpg(1, sig)` is `kill(-1, sig)`: SIGTERM to **every process of the user**. It ended the WSL session and killed another agent's N0 ticket 11 Linux run (`t11_lin.sh`, started 03:42:47). No lock was left behind. Rule 2.3 exists because of this.

## 1. Design

### 1.1 Architecture
- **Processes are created with `posix_spawn` through FFM, never `fork`** (D2). `os.fork`, `forkpty`, `os.exec*`, `os.spawn*`, `register_at_fork` stay absent. `preexec_fn`, `user`, `group`, `extra_groups`, `umask` raise (1.7) until the Rust spawn helper of "Decisions" item 2 exists.
- Everything N1 adds exists only when `IoFd.KERNEL` (kernel descriptors). In `PYJVM_POSIX_FDS=nio` mode the old state remains: `_posixsubprocess` is the `StubModules` shell and `os.waitpid`/`wait` are the `ChildProcessError` lambdas (`PosixFiles.java:41-42`). N0 ticket 14 removes that mode later.
- New POSIX-only classes (never initialised on Windows):
  - `PosixProc`: FFM for `waitpid`, `waitid`, `wait4`, `kill`, `killpg`, `getpgid`, `getpgrp`, `getsid`, `setpgid`, `sysconf`, `confstr`, `syscall` (pidfd_open), `faccessat`, `__errno_location`; the blocking-wait rule (1.5).
  - `PosixSpawn`: FFM for `posix_spawn`, `posix_spawnp`, the file-action and attribute functions, `environ`; one entry point `spawn(Plan)`.
  - `PosixOsProc`: the `posix` module functions of this phase, registered over the shared ones by a call at the end of `PosixOsFds.install` (a POSIX-only file, so `PosixFiles.install` is not edited).
  - `PosixSubprocessModule`: `_posixsubprocess`.
  - `PosixConfNames`: generated `sysconf`/`confstr` name tables, data only (no FFM import).
  - `PosixStructSeq`: a small structseq factory (tuple subclass with named fields; module-qualified repr) for `os.waitid_result` and `resource.struct_rusage`; no FFM. N2 reuses it for `pwd`/`grp`/`resource`.
- Shared files get the smallest possible edit: `ImportSupport.createNative` (one expression), `SignalModule` (one added accessor), `StubModules.resource` (one line, ticket 8), `tests/test_import_runtime.py` (class list). `SignalModule.kill` is **not** edited: `PosixOsProc` re-registers `kill` and calls `SignalModule.kill` only for the process's own pid.
- The emulated working directory stays (N0 1.1). Every spawn adds a chdir file action to it when `PosixModule.cwd != null` (1.4).

### 1.2 FFM conventions
N0 section 1.3 applies unchanged (handles through `PosixNative.fn`, errno capture, thread-local call state, no `Linker.Option.critical`, explicit offsets, `PosixNative.rethrow`). Additions:
- The `posix_spawn*` family **returns the error number** instead of setting errno: build those handles with `PosixNative.fnNoCapture` and use the return value. This is also why a failed spawn needs no errno read.
- All memory passed to `posix_spawn` (path, argv, envp, file actions, attributes, the pid cell) is native, from one confined `Arena` per call. The child shares the address space until it execs (glibc uses `clone(CLONE_VM | CLONE_VFORK)`), and the garbage collector may move heap objects meanwhile: nothing heap-backed may be passed.
- `posix_spawn_file_actions_t` and `posix_spawnattr_t` are opaque buffers (80 and 336 bytes, 8-aligned). Only the libc functions touch them; `..._init` before use and `..._destroy` in `finally`.
- `sigset_t`: 128 bytes, zero-filled by the Arena; signal `n` (1..64) is bit `n - 1` of the 64-bit word at offset 0. Do not call `sigemptyset`/`sigaddset`.
- `syscall` is variadic: `PosixNative.fn("syscall", of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(1))`.
- `environ` is a variable, not a function: `LINKER.defaultLookup().findOrThrow("environ").reinterpret(8).get(ADDRESS, 0)`, read **at every spawn** (measured: the pointer changes after a `setenv` of a new variable). `PosixNative` gets a package-private `symbol(String)` for this.

### 1.3 Errors
- `PosixNative.osError(errno, filename)` as in N0. Functions of this phase that fail by errno have no filename, except `os.posix_spawn[p]` (the `path` argument as the caller passed it, bytes staying bytes; 3.6).
- Integer arguments: `PosixOsProc.cInt(Object)` converts to a C int with CPython's texts: a non-integer -> `TypeError: '<type>' object cannot be interpreted as an integer`; out of the 32-bit range -> the lane's OverflowError (3.8). The signal argument of `kill` is the exception: it is taken as a 64-bit value and truncated to its low 32 bits (measured: `os.kill(pid, 2**40)` behaves as signal 0). The signal of `killpg` is range-checked as a C int like the other arguments (corrected at ticket 3 review, measured).
- `sysconf`/`confstr` report "no value" by a return of -1/0 **without touching errno**, and an invalid name by EINVAL. A captured errno is stale after a call that did not set it (N0 7.2), so: write 0 through `__errno_location()` (handle without capture, result `reinterpret(4)`), then make the call; a failing return with captured errno 22 on **two consecutive attempts** is `OSError(22)`; anything else is "no value" (`sysconf` returns -1, `confstr` returns `None`). Reason for two attempts: JVM-internal code may change errno between the two downcalls; it cannot do so to exactly 22 twice in practice, and a real EINVAL always repeats.

### 1.4 The spawn core (`PosixSpawn`)
`static long spawn(Plan p)`: the child's pid, or `-errno`. It never raises for an OS error. `Plan` holds plain Java values: `byte[] path`; `byte[][] argv` (null = a NULL `argv` pointer); `byte[][] envp` (null = libc `environ` read at call time); `boolean usePath` (`posix_spawnp`); an ordered action list of `{kind, int a, int b, int c, byte[] path}` with kinds `OPEN(fd, path, oflag, mode)`, `CLOSE(fd)`, `DUP2(fd, newfd)`, `CHDIR(path)`, `CLOSEFROM(lowfd)`; `int flags`; `int pgroup`; `long sigmask`; `long sigdefault`; output field `boolean failedAtAdd`.
1. Build the C strings, the NULL-terminated `argv` and `envp` pointer arrays, the 4-byte pid cell.
2. `posix_spawn_file_actions_init`; add each action in list order with `addopen`, `addclose`, `adddup2`, `addchdir_np`, `addclosefrom_np`. A non-zero return from an add function ends the call with `-thatValue` and `failedAtAdd = true` (measured: `addclose(-1)` returns EBADF).
3. `posix_spawnattr_init`; `setflags((short) flags)`; if `POSIX_SPAWN_SETPGROUP` `setpgroup`; if `SETSIGMASK` `setsigmask`; if `SETSIGDEF` `setsigdefault`.
4. `posix_spawn` or `posix_spawnp(pidCell, path, actions, attr, argv, envp)`; return the pid or `-rc`.
5. `finally`: destroy the attributes and the file actions; the Arena closes.
Rules for every caller:
- **Signal mask.** `POSIX_SPAWN_SETSIGMASK` is always set. The mask is the caller's explicit one, else `SignalModule.blockedMask()` (new: the Python-level `blocked[]` set as bits `1L << (sig - 1)`, taken under `LOCK`). Reasons: the calling JVM thread's kernel mask must not leak (U1: SIGQUIT blocked), and CPython children inherit the mask set with `signal.pthread_sigmask` (measured: parent blocks SIGUSR1 -> child `SigBlk` 0x200, through both `os.posix_spawn` and `subprocess`).
- **Emulated cwd.** `PosixSpawn.cwdAction()` returns `CHDIR(utf8(PosixModule.cwd.toString()))` or null. Callers put it **before** any action that names a relative path (their own `CHDIR`, `OPEN`). Measured with C: two chdir actions in a row work (absolute, then relative), a relative program path and a `posix_spawnp` PATH search are resolved after the chdir actions.
- **Environment.** `envp == null` means the live libc environment. Never build an environment from `System.getenv()` (a start-up snapshot that does not see `os.putenv`; this is the bug `putenv_child_plan.md` section 5 warns about).
- **No descriptor is created in the parent by the core.** Callers that create temporaries (`dupAbove`, pipes) own them, `adopt` them and close them in `finally` (N0 1.7).
- Known limit, documented, not fixed: if the directory of the emulated cwd has been removed or renamed since `os.chdir`, the chdir action fails with ENOENT and nothing can be spawned (CPython's child would start in the unlinked directory).

### 1.5 D5 for waits: Python signal handlers while the main thread waits for a child
Same facts as N0 1.6: handlers run only on the main thread at `SignalModule.dispatchPending()`; a blocked `waitpid(2)` on the main thread is generally not interrupted by a signal. One function serves `waitpid`, `wait`, `waitid`, `wait3`, `wait4`:
```
wait(call, options, target):                      // call(opts) -> pid/0/-errno  (waitid: 0 with si_pid == 0 means "nothing")
  if (options & WNOHANG) != 0  or  !(SignalModule.active && SignalModule.isMain()):
      loop: r = call(options); if r == -EINTR { SignalModule.dispatchPending(); continue }; return r
  // main thread, signals active, caller wants to block
  pidfd = -1; delay = 1 ms
  try:
    loop:
      r = call(options | WNOHANG)                 // never blocks
      if r != "nothing": return r                 // a status, or an error such as ECHILD
      SignalModule.dispatchPending()
      if target is one process (waitpid/wait4 with pid > 0, waitid P_PID) and the options ask for exit only
           (no WUNTRACED/WSTOPPED/WCONTINUED):
          if pidfd == -1: pidfd = pidfd_open(pid)          // syscall 434; on any failure pidfd = -2
      if target is waitid P_PIDFD with exit only: use the caller's descriptor as pidfd (do not close it)
      if pidfd >= 0: poll({pidfd, POLLIN}, 1, 50)          // readable when the process has exited; EINTR is fine
      else:          SignalModule.sleep(delay ms); delay = min(delay * 2, 50)
  finally: close a pidfd this call opened
```
- A pid cannot be reused while it is an unreaped child, so the pidfd names the right process; if the child was reaped elsewhere, the next `call` returns ECHILD.
- Latency: none for a single child (measured anchor in `os_wait_signals`: 20 `/bin/true` children, mean spawn+wait below 40 ms); up to `min(time waited, 50 ms)` for "any child" waits and for stop/continue waits. A handler runs at most 50 ms late. Off the main thread, or before `_signal` is imported, the wait is one blocking call.
- A handler that raises propagates out of the wait; the child stays unreaped, as in CPython (measured: `TimeoutError` out of `os.waitpid`).
- The transient pidfd is close-on-exec by construction (measured `F_GETFD` = 1), is adopted and closed through `PosixFds`, and is never visible to Python.

### 1.6 Children, the JDK and the JVM
- The JDK reaps only children `ProcessImpl` started, one reaper thread per pid (U1). pyJVM's children are invisible to it, so their status stays for `os.waitpid`. The reverse is a real hazard: `os.wait()`, `os.waitpid(-1, ...)`, `os.waitid(P_ALL, ...)` may collect a JDK-started child (today only `ExecSupport.HostPythonBackend`, used when the self-hosted compiler is not on the class path; the JDK then reports exit status 0 for it). Documented in JVM_BACKEND (ticket 12); no guard. Rule for the runtime: after N1 no new `ProcessBuilder` use is added, and on POSIX kernel mode `os.kill` no longer starts `/bin/kill`.
- `os.kill(os.getpid(), sig)` keeps its in-process delivery (`SignalModule.kill`, synchronous, as CPython's handler runs before `os.kill` returns: measured). Every other target goes to libc `kill`. When the target set includes the JVM itself (pid 0, a negative pid, -1), the JVM receives a real signal: a Python handler runs only for signals hooked through `sun.misc.Signal`; otherwise HotSpot's own disposition applies (U5).
- Differences from CPython that `posix_spawn` cannot express, recorded as gaps (JVM_BACKEND, ticket 12): (a) CPython itself ignores SIGPIPE and SIGXFSZ, so a child started with `restore_signals=False` or with plain `os.posix_spawn` has them ignored (measured `SigIgn` 0x1001000); under pyJVM they are default (U1). (b) children inherit the JVM's raised `RLIMIT_NOFILE`. (c) the child's start state is produced by glibc's order (signals, session, group, then file actions), not CPython's (descriptors, chdir, signals, session, group); only the choice of which error is reported for a doubly-wrong call can differ.

### 1.7 `_posixsubprocess.fork_exec` on the spawn core
Arguments, in order (22; 23 on 3.13, whose last one `allow_vfork` is ignored): `args, executable_list, close_fds, fds_to_keep, cwd, env_list, p2cread, p2cwrite, c2pread, c2pwrite, errread, errwrite, errpipe_read, errpipe_write, restore_signals, call_setsid, pgid_to_set, gid, extra_groups, uid, child_umask, preexec_fn`.

Unsupported arguments (pyJVM-specific texts; tested by pytest, never by a conformance case), checked after CPython's own argument checks and before anything is spawned:
- `preexec_fn is not None` -> `RuntimeError("preexec_fn is not supported by pyJVM: there is no fork, so no Python code can run in the child")`;
- `uid is not None` -> `NotImplementedError("subprocess: user is not supported by pyJVM (posix_spawn cannot change the user id)")`; `gid` -> the same with `group`/`group id`; `extra_groups is not None` -> `extra_groups`/`supplementary groups`; `child_umask >= 0` -> `umask`/`umask`.

The plan (`Plan`) for one call:
1. Temporaries in the parent (CPython dups inside the child; file actions cannot pick a free number): if `c2pwrite == 0` replace it by `PosixFds.dupAbove(0, 3)`; if `errwrite` is 0 or 1 replace it likewise. `dupAbove(fd, min)` = `fcntl(fd, F_DUPFD_CLOEXEC, min)`, adopted; the temporaries are closed in the outer `finally`.
2. Actions, in this order:
   a. for each `fd` in `fds_to_keep` except `errpipe_write`: `DUP2(fd, fd)` (glibc clears close-on-exec for equal descriptors: measured). **`errpipe_write` is never made inheritable**: the parent writes the error record itself (step 5), and a child holding the write end would keep `subprocess` blocked in `os.read(errpipe_read)` until the child exits;
   b. `CLOSE` for each of `p2cwrite`, `c2pread`, `errread`, `errpipe_read` that is not -1;
   c. stdin: `p2cread == 0` -> `DUP2(0, 0)`; else if not -1 -> `DUP2(p2cread, 0)`. stdout: `c2pwrite == 1` -> `DUP2(1, 1)`; else if not -1 -> `DUP2(c2pwrite, 1)`. stderr: `errwrite == 2` -> `DUP2(2, 2)`; else if not -1 -> `DUP2(errwrite, 2)`;
   d. `cwdAction()` if not null; then `CHDIR(fsencode(cwd))` if `cwd is not None`;
   e. if `close_fds`: with `K` = {0, 1, 2} plus `fds_to_keep` minus `errpipe_write` and `maxK` its largest member: `CLOSE(f)` for every descriptor `f` currently open in the parent (`PosixFds.listFds()`, a `/proc/self/fd` reader that ticket 4 adds; since N0 ticket 12 `closeRange` walks the generation table instead) with `3 <= f < maxK` and `f` not in `K`; then `CLOSEFROM(maxK + 1)`. (Measured: `CLOSE` of a descriptor that is not open is not an error; `CLOSEFROM` closes everything from its argument up, so it cannot be used below `maxK`.)
3. Attributes: `SETSIGMASK` with `SignalModule.blockedMask()`; `restore_signals` -> `SETSIGDEF` with SIGPIPE (13) and SIGXFSZ (25); `call_setsid` -> `SETSID`; `pgid_to_set >= 0` -> `SETPGROUP` with that value.
4. `argv` = each item of `args` through `os.fsencode` rules (str -> UTF-8 surrogateescape, bytes, PathLike); `args is None` -> NULL. `envp` = the bytes of `env_list`, or null (libc environ) when `env_list is None`.
5. Try each entry of `executable_list` (bytes) with `posix_spawn` (never `posix_spawnp`: the list already is the PATH expansion). Success -> return the pid. Failure: remember errno by CPython's rule (measured, `posixsubprocess_errpipe` lines `list1`-`list4`): **the first errno that is neither ENOENT nor ENOTDIR; if there is none, the last errno**. An empty list -> record `SubprocessError:0:`.
6. All failed: classify, write one record to `errpipe_write` with `PosixFds.write` (it is a pipe with an empty buffer: it cannot block), and return a pid (step 7). Record = `b"OSError:" + lowercase hex errno + b":" + suffix`:
   - errno EBADF -> suffix `noexec` (exec never returns EBADF; it is a bad stdio or kept descriptor: measured `OSError:9:noexec`);
   - else, if the plan has a chdir action: diagnose in the parent, the emulated cwd first, then `cwd` (joined to the emulated cwd when relative): `fstatat` fails -> that errno; not a directory -> ENOTDIR; `faccessat(AT_FDCWD, path, X_OK, AT_EACCESS)` fails -> that errno. A hit -> `OSError:<hex of the diagnosed errno>:noexec:chdir` (measured: missing 2, a file 0x14, no search permission 0xd, `""` 2, and a bad cwd wins over a missing program);
   - else, errno EPERM or ESRCH and `pgid_to_set >= 0` -> suffix `noexec` (measured `OSError:1:noexec` for `process_group=1`);
   - else an exec failure: empty suffix (`OSError:2:`, `OSError:d:`, `OSError:14:`, `OSError:8:`).
   `subprocess._execute_child` turns the record into the exception with the right filename (`cwd` for `noexec:chdir`, none for `noexec`, the original executable otherwise). The time between the failed spawn and the diagnosis is a small race; accepted.
7. **The pid returned after a failure (decision; owner question Q1).** CPython returns the pid of the forked child, which exits with status 255; `subprocess` then calls `os.waitpid(pid, 0)` and gets `returncode == 255` (measured: `fork_exec` returns a pid and wait status 65280 for every failure, and the failed `Popen` object has `returncode 255`, `_child_created True`). After a failed `posix_spawn` no child exists (measured: `waitpid(-1, WNOHANG)` -> ECHILD). `fork_exec` therefore starts a **stand-in child** that does exactly that and returns its pid: `posix_spawn("/bin/sh", ["sh", "-c", "exit 255"])`, libc environ, one action `CLOSEFROM(3)`, `SETSIGMASK` with an empty mask. It is a real child with a real status; nothing in `os.waitpid` is special-cased. If the stand-in cannot be started, return `getpid()`: `os.waitpid(own pid)` is ECHILD (measured), which `subprocess` handles (`except ChildProcessError: pass`); the only visible difference is `returncode None` on the discarded object and a ResourceWarning from its `__del__` when warnings are enabled.

### 1.8 Descriptor lifetime
- N0 1.7 holds: every descriptor this phase creates in the parent (`dupAbove` temporaries, `os.pidfd_open`, the transient pidfd of 1.5) is created close-on-exec, `adopt`ed after success, and closed through `PosixFds.close` (which `forget`s first).
- POSIX `subprocess` closes its pipe ends with `os.close` explicitly, so no forced collection is needed (unlike `_winapi.CreateProcess`; parent plan N1). `subprocess_posix` has the lines that would hang otherwise (child `cat` reading until EOF; 320 KB through `communicate`).
- Inheritance is the kernel's: a descriptor reaches a child only if it is not close-on-exec at spawn time (what Python made inheritable, or a `DUP2` action). The runtime never iterates "all descriptors" except to build `CLOSE` actions for the child (parent plan risk 7) and for the rule below.
- **The JVM's own inheritable descriptors (added 2026-11-24 after spike S2, `work/n1/spike.md`).** The spike found the JDK's jimage file (`$JAVA_HOME/lib/modules`, fd 3 there) open **without** close-on-exec, so it reaches every child spawned without a closing action. CPython has no such descriptor. Rule: before every spawn whose actions do not end with `CLOSEFROM(3)` (`fork_exec` with `close_fds=False`, `os.posix_spawn[p]`, the `os.system` path), `PosixSpawn` lists the parent's descriptors (`PosixFds.listFds()`, a `/proc/self/fd` reader; ticket 12 removed the old listing from `closeRange`, so ticket 4 adds it back as a helper) and adds `CLOSE(f)` for every `f > 2` that is the JVM's own: `PosixFds.gen(f) == 0` (never adopted by Python), not close-on-exec, and whose `/proc/self/fd/f` target is a file under `java.home` or a class-path/module-path entry of this JVM. Descriptors inherited by the JVM from its launcher (also `gen` 0) have other targets and pass through as in CPython, as do descriptors Python made inheritable. Nothing in the parent is changed (no `fcntl` on JVM descriptors). Ticket 4 first lists, in a running pyJVM, every descriptor > 2 with its target and flags, and widens the target rule if the JVM holds another inheritable one. Test (ticket 4 pytest, ticket 6 again for `fork_exec`): a `close_fds=False` child of pyJVM sees no descriptor > 2 except one the script made inheritable and one passed into pyJVM by the test's outer process (`pass_fds`). Cost: one listing per such spawn (spike item 9: 0.25-0.4 ms). Accepted gap (JVM_BACKEND, ticket 12): the spike also saw a JDK directory stream hold a descriptor **without** close-on-exec while it is open (`spike.md`, fd 5); a JDK directory listing on another thread at the moment of a spawn without `CLOSEFROM(3)` can pass that descriptor to the child. `listFds()` itself closes its stream before the actions are built, so it never leaks its own. **Note 2026-11-24 (ticket 4):** the listing in a running pyJVM found fd 3 `lib/modules`, fd 4 `/dev/random`, fd 5 `/dev/urandom` (JDK NativePRNG), all without close-on-exec; the target rule is therefore: under `java.home`, equal to or under a `java.class.path`/`jdk.module.path` entry, or `/dev/random`/`/dev/urandom`. Gaps against CPython (ticket 12 puts both in JVM_BACKEND): (a) a descriptor the JVM *inherited* from its launcher whose target matches that rule is closed in children (CPython would pass it); (b) `gen(f) == 0` is re-checked when each `CLOSE` is added, just before the call, so only the few native calls between that add and `posix_spawn` remain as a window in which a JDK descriptor could close and Python reuse its number.

## 2. Environment and workflow

### 2.1 Trees
- Work continues in the N0 worktree layout (N0 2.1; on the E: machine the repo is `E:\pyjvm`, scratch and lock are in `E:\pyjvm-work`, the WSL clone is `~/pyjvm-n0` with a remote `win`). Branch **`n1-proc`**, created from **B1 = the N0 ticket 13 commit, `68a5099`** (also origin `main` since 2026-11-24; do not start while N0 tickets 11-13 are uncommitted or the tree is dirty). One local commit per ticket, never pushed unless the owner asks.
- Scratch for this phase: `E:\pyjvm-work\n1\` (Windows) and `~/pyjvm-work/n1/` (WSL). The measurement scripts and draft cases named in section 0 are there; ticket 0 copies them into the repo under `work/n1/`.

### 2.2 Rules for every ticket
N0 section 2.2 applies word for word (the run lock for every build/test/gate on either OS, one gate at a time, uv-managed Pythons, WSL commands as script files, reviewer gets ticket text + diff + logs, "not run" said explicitly). The sets:
- **W-min (Windows):** runtime compiles (`javac --release 25`), `python -m pyjvm315.methodsize --runtime` reports 0 methods over 8000 bytes, `pytest tests/test_import_runtime.py -q`, and conformance cases `os_pipe, os_platform, io_files, select_files, signal_basic, subprocess_windows, os_putenv_child, native_stdlib_modules` on the 3.15 lane, plus every case the ticket adds (each prints only its first line on Windows).
- **L-min (WSL, three lanes):** `io_files, io_posix, io_memory, os_fchmod, os_putenv, os_platform, select_files, signal_basic, encoding_defaults` (N0's set).
- **P-min (WSL, three lanes):** the process cases that exist at that ticket, of `os_sysconf, os_posix_spawn, os_wait_kill, os_wait_signals, subprocess_posix, subprocess_posix_errors, posixsubprocess_errpipe, os_wait_rusage, os_putenv_child`, plus `os_pipe, signal_blocking_io, select_pipes, os_fd_flags`.
- **W-full / L-full** (ticket 12 only): pytest, methodsize, full conformance on three lanes; L-full also the strict lane; compared by case name with ticket 0's baseline.

### 2.3 Safety rules for anything that sends signals (tests, cases, scratch scripts)
1. Never call `os.kill` or `os.killpg` with a real signal (anything but 0) on pid -1, pid 0, a negative pid, pgid 0 or pgid 1. `killpg(1, sig)` is `kill(-1, sig)`.
2. Only signal children the script itself created, by their pid or their own process group (`process_group=0` / `setpgroup=0`), and the script's own pid.
3. The conformance runner and the JVM under test share a process group: no `killpg(os.getpgrp(), sig)` with a real signal.
4. Every child that sleeps is `sleep 30` at most and is killed and reaped by the script; every `subprocess.run` that could hang has `timeout=` (the runner has no per-case timeout).
5. A reviewer rejects a diff that violates 1-3 without running it.

## 3. Reference data (Linux x86-64, glibc 2.39; measured unless marked)

### 3.1 libc functions (all present in `libc.so.6`; symbol version in the notes)
| C prototype | FunctionDescriptor | Notes |
| --- | --- | --- |
| `pid_t waitpid(pid_t, int*, int)` | `of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)` | status = `JAVA_INT` at offset 0 |
| `int waitid(idtype_t, id_t, siginfo_t*, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)` | zero the 128-byte buffer before every call |
| `pid_t wait4(pid_t, int*, int, struct rusage*)` | `of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)` | `wait3(opts)` is `wait4(-1, ...)` |
| `int kill(pid_t, int)`, `int killpg(int, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT)` | `killpg` with a negative pgid returns EINVAL in libc; pgid 1 is a broadcast (2.3) |
| `pid_t getpgid(pid_t)`, `pid_t getsid(pid_t)` | `of(JAVA_INT, JAVA_INT)` | |
| `pid_t getpgrp(void)`, `pid_t getpid(void)` | `of(JAVA_INT)` | no capture (cannot fail) |
| `int setpgid(pid_t, pid_t)` | `of(JAVA_INT, JAVA_INT, JAVA_INT)` | |
| `long sysconf(int)` | `of(JAVA_LONG, JAVA_INT)` | errno rule 1.3 |
| `size_t confstr(int, char*, size_t)` | `of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)` | returns the needed size including the NUL; errno rule 1.3 |
| `int* __errno_location(void)` | `of(ADDRESS)` | no capture |
| `long syscall(long, ...)` | `of(JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT)` | `firstVariadicArg(1)`; `SYS_pidfd_open` = 434 (kernel 5.3+); returns -1 with errno |
| `int faccessat(int, const char*, int, int)` | `of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)` | `AT_FDCWD` -100, `X_OK` 1, `AT_EACCESS` 0x200 |
| `int posix_spawn(pid_t*, const char*, const posix_spawn_file_actions_t*, const posix_spawnattr_t*, char *const argv[], char *const envp[])`, `posix_spawnp` | `of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)` | no capture; returns the error. Two versions exist (`@@GLIBC_2.15` default, `@GLIBC_2.2.5` compat with a `/bin/sh` fallback); `dlsym` gives the default one (U1; the spike checks that a script without `#!` returns ENOEXEC) |
| `posix_spawn_file_actions_init/destroy(fa*)` | `of(JAVA_INT, ADDRESS)` | no capture, as all below |
| `..._addopen(fa*, int fd, const char*, int oflag, mode_t)` | `of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)` | copies the path |
| `..._addclose(fa*, int)` | `of(JAVA_INT, ADDRESS, JAVA_INT)` | EBADF for a negative fd |
| `..._adddup2(fa*, int, int)` | `of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)` | equal fds: clears close-on-exec in the child (glibc 2.29+) |
| `..._addchdir_np(fa*, const char*)` | `of(JAVA_INT, ADDRESS, ADDRESS)` | glibc 2.29 |
| `..._addclosefrom_np(fa*, int)` | `of(JAVA_INT, ADDRESS, JAVA_INT)` | glibc 2.34 |
| `posix_spawnattr_init/destroy(attr*)` | `of(JAVA_INT, ADDRESS)` | |
| `posix_spawnattr_setflags(attr*, short)` | `of(JAVA_INT, ADDRESS, JAVA_SHORT)` | |
| `posix_spawnattr_setpgroup(attr*, pid_t)` | `of(JAVA_INT, ADDRESS, JAVA_INT)` | |
| `posix_spawnattr_setsigmask(attr*, const sigset_t*)`, `..._setsigdefault` | `of(JAVA_INT, ADDRESS, ADDRESS)` | |
| `char **environ` | data symbol | read the pointer at every call (1.2) |
`requireSupportedHost` already rejects non-glibc hosts; `PosixSpawn`'s static initialiser additionally fails with the same ImportError text plus `" (glibc 2.34 or newer is required for process creation)"` when `posix_spawn_file_actions_addclosefrom_np` is not found.

### 3.2 Struct layouts
- `posix_spawn_file_actions_t` 80 bytes, `posix_spawnattr_t` 336 bytes, `sigset_t` 128 bytes: opaque, see 1.2.
- `siginfo_t` (for `waitid`), size 128: `si_signo` i32 @0, `si_errno` i32 @4, `si_code` i32 @8, `si_pid` i32 @16, `si_uid` u32 @20, `si_status` i32 @24.
- `struct rusage`, size 144: `ru_utime` {tv_sec i64 @0, tv_usec i64 @8}, `ru_stime` {@16, @24}, then fourteen i64: `ru_maxrss` @32, `ru_ixrss` @40, `ru_idrss` @48, `ru_isrss` @56, `ru_minflt` @64, `ru_majflt` @72, `ru_nswap` @80, `ru_inblock` @88, `ru_oublock` @96, `ru_msgsnd` @104, `ru_msgrcv` @112, `ru_nsignals` @120, `ru_nvcsw` @128, `ru_nivcsw` @136.
- wait status (int): exited `(code << 8)`; signalled `sig | 0x80 if core`; stopped `(sig << 8) | 0x7f`; continued `0xffff`.

### 3.3 Constants (decimal unless hex)
- spawn flags: `POSIX_SPAWN_RESETIDS` 1, `SETPGROUP` 2, `SETSIGDEF` 4, `SETSIGMASK` 8, `SETSCHEDPARAM` 16, `SETSCHEDULER` 32, `USEVFORK` 64, `SETSID` 128 (glibc 2.26).
- wait: `WNOHANG` 1, `WUNTRACED` 2, `WSTOPPED` 2, `WEXITED` 4, `WCONTINUED` 8, `WNOWAIT` 16777216; `P_ALL` 0, `P_PID` 1, `P_PGID` 2, `P_PIDFD` 3; `CLD_EXITED` 1, `CLD_KILLED` 2, `CLD_DUMPED` 3, `CLD_TRAPPED` 4, `CLD_STOPPED` 5, `CLD_CONTINUED` 6.
- `os` module values: `POSIX_SPAWN_OPEN` 0, `POSIX_SPAWN_CLOSE` 1, `POSIX_SPAWN_DUP2` 2; `EX_OK` 0, `EX_USAGE` 64, `EX_DATAERR` 65, `EX_NOINPUT` 66, `EX_NOUSER` 67, `EX_NOHOST` 68, `EX_UNAVAILABLE` 69, `EX_SOFTWARE` 70, `EX_OSERR` 71, `EX_OSFILE` 72, `EX_CANTCREAT` 73, `EX_IOERR` 74, `EX_TEMPFAIL` 75, `EX_PROTOCOL` 76, `EX_NOPERM` 77, `EX_CONFIG` 78. **Absent on the reference interpreters, do not add:** `POSIX_SPAWN_CLOSEFROM`, `PIDFD_NONBLOCK`, `EX_NOTFOUND`.
- errno: `EPERM` 1, `ENOENT` 2, `ESRCH` 3, `EINTR` 4, `ENOEXEC` 8, `EBADF` 9, `ECHILD` 10, `EACCES` 13, `ENOTDIR` 20, `EINVAL` 22. Signals: `SIGPIPE` 13, `SIGCHLD` 17, `SIGXFSZ` 25; valid signal numbers 1..64.
- `sysconf_names`: 134 names; `confstr_names`: 27 names; identical on the three lanes (the `SYSCONF_TABLE`/`CONFSTR_TABLE` lines of `m1-31x.txt`; the generator of ticket 0 produces the Java arrays). Anchors: `SC_ARG_MAX` 0, `SC_CLK_TCK` 2, `SC_OPEN_MAX` 4, `SC_PAGESIZE` = `SC_PAGE_SIZE` 30, `SC_NPROCESSORS_ONLN` 84; `CS_PATH` 0, `CS_GNU_LIBC_VERSION` 2, `CS_GNU_LIBPTHREAD_VERSION` 3.

### 3.4 Surface on the reference interpreters (measured, identical on the three lanes)
In scope for N1 (`__text_signature__`): `posix_spawn(path, argv, env, /, *, file_actions=(), setpgroup=None, resetids=False, setsid=False, setsigmask=(), setsigdef=(), scheduler=None)` and `posix_spawnp` (same); `waitpid(pid, options, /)`; `wait()`; `wait3(options)`; `wait4(pid, options)` (the last two accept keywords); `waitid(idtype, id, options, /)`; `waitid_result` (type; 5 fields `si_pid, si_uid, si_signo, si_status, si_code`); `waitstatus_to_exitcode(status)`, `WIFEXITED`, `WEXITSTATUS`, `WIFSIGNALED`, `WTERMSIG`, `WIFSTOPPED`, `WSTOPSIG`, `WIFCONTINUED` (keyword `status` allowed), `WCOREDUMP(status, /)`; `kill(pid, signal, /)`, `killpg(pgid, signal, /)`; `getpgid(pid)` (keyword allowed), `getpgrp()`, `getsid(pid, /)`, `setpgid(pid, pgrp, /)`; `sysconf(name, /)`, `confstr(name, /)`, `sysconf_names`, `confstr_names` (plain dicts); `pidfd_open(pid, flags=0)` (keywords allowed); the constants of 3.3. `_posixsubprocess` has exactly one name, `fork_exec`.
Present in CPython and **out of N1** (stay absent; ticket 12 lists them in JVM_BACKEND): `fork`, `forkpty`, `execv`, `execve`, `_exit` exists already, `abort`, `setsid`, `setpgrp`, `tcgetpgrp`, `tcsetpgrp`, `times`, `nice`, `getpriority`, `setpriority`, `sched_*`, `sched_param`, `getlogin`, `uname`, `ctermid`, `getgroups`, `getres[ug]id`, `set*id`, `setgroups`, `initgroups`, `getloadavg`, `register_at_fork`, `unshare`, `setns`, and `os.py`'s `spawn*`/`P_WAIT` (defined there only when `fork` exists). `os.popen` is Python code on `subprocess` and works once N1 is in. `os.system` is ticket 10 (owner question Q2).

### 3.5 `posix_spawn` behaviour (measured with C, glibc 2.39)
- Failure returns the errno and leaves **no child** (`waitpid(-1, WNOHANG)` -> ECHILD) and does not write the pid cell: missing file 2, not executable 13, a directory 13, a path through a file 20, a script without `#!` **8** (also with `posix_spawnp`: no `/bin/sh` fallback), a `#!` naming a missing interpreter 2, an empty path 2.
- A failing file action or attribute gives the same kind of return, indistinguishable from an exec failure: chdir to a missing directory 2, to a file 20, to a directory without search permission 13, to `""` 2; `adddup2(9999, 1)` 9; `addopen` of a path in a missing directory 2; `setpgroup(1)` 1. `addclose` of a descriptor that is not open is ignored.
- `adddup2(fd, fd)` keeps `fd` open in the child although it is close-on-exec in the parent. `addclosefrom_np(3)` closes everything from 3 up, including descriptors made inheritable by an earlier action.
- A child inherits the caller's signal mask and ignored signals; `SETSIGMASK` with an empty set and `SETSIGDEF` reset them. `SETSID`: the child's session and group ids equal its pid. `SETPGROUP` 0: group id equals pid, session unchanged.
- `syscall(434, pid, 0)` gives a close-on-exec descriptor; `poll` on it times out while the child runs and reports `POLLIN` once it has exited (before it is reaped); `waitid(P_PID, pid, WEXITED | WNOWAIT)` then shows `si_signo` 17, `si_code` 1 (`CLD_EXITED`) and leaves the child waitable.

### 3.6 CPython behaviour to match (measured on the three lanes; identical unless 3.8 says otherwise)
sysconf / confstr:
- `os.sysconf("SC_PAGESIZE")` == `os.sysconf(30)`; `os.sysconf(True)` is `os.sysconf(1)`. Unknown string -> `ValueError: unrecognized configuration name`; unknown number (`99999`, `-5`) -> `OSError(22)`; float, `None`, bytes -> `TypeError: configuration names must be strings or integers`; `2**40` -> `OverflowError: Python int too large to convert to C int` (all lanes); no argument -> `TypeError: posix.sysconf() takes exactly one argument (0 given)`; keyword -> `TypeError: posix.sysconf() takes no keyword arguments`. A name without a value returns -1 (`SC_SEM_NSEMS_MAX`). Of the 134 names exactly one raises on this host: `SC_EQUIV_CLASS_MAX` -> `OSError(22)` (it comes from libc; do not special-case it).
- `os.confstr("CS_GNU_LIBC_VERSION")` -> `'glibc 2.39'`, `CS_GNU_LIBPTHREAD_VERSION` -> `'NPTL 2.39'`, `CS_PATH` -> `'/bin:/usr/bin'`; several names return `''`; the same error rules with `posix.confstr()` in the two argument-count texts. `subprocess._USE_POSIX_SPAWN` becomes True from these.
W* macros and `waitstatus_to_exitcode` (argument is a C int: `'str' object cannot be interpreted as an integer`; `2**31` and `2**40` -> `OverflowError: Python int too large to convert to C int` on all lanes):
- tuple `(WIFEXITED, WEXITSTATUS, WIFSIGNALED, WTERMSIG, WIFSTOPPED, WSTOPSIG, WIFCONTINUED, WCOREDUMP)`: `0` -> `(True, 0, False, 0, False, 0, False, False)`; `0xff00` -> `(True, 255, False, 0, False, 255, False, False)`; `9` -> `(False, 0, True, 9, False, 0, False, False)`; `0x8b` -> `(False, 0, True, 11, False, 0, False, True)`; `0x137f` -> `(False, 19, False, 127, True, 19, False, False)`; `0xffff` -> `(False, 255, False, 127, False, 255, True, True)`; `0x1ff00` -> `(True, 255, False, 0, False, 255, False, False)`; `-1` -> `(False, 255, False, 127, False, 255, False, True)`.
- `waitstatus_to_exitcode`: exited -> the code (`0x1ff00` -> 255, `0x80` -> 0); signalled -> `-sig`; stopped -> `ValueError: process stopped by delivery of signal 19` (and `signal 0` for `0x7f`); `0xffff` and `-1` -> `ValueError: invalid wait status: 65535` / `-1`. (The existing shared lambda in `PosixFiles.java:51` gives "invalid wait status" for the stopped case and has no C-int check; `WCOREDUMP` is missing.)
wait family:
- No children: `waitpid(-1, 0)`, `wait()`, `waitpid(own pid, 0)`, `waitpid(1, WNOHANG)`, `waitpid(0, WNOHANG)`, `wait3(0)`, `wait4(-1, 0)`, `waitid(P_ALL, 0, WEXITED)`, `waitid(P_PID, own pid, WEXITED)` -> `ChildProcessError(10, 'No child processes')`. `waitpid(-1, 12345678)`, `waitid(P_ALL, 0, 0)`, `waitid(99, 0, WEXITED)`, `wait3(12345678)` -> `OSError(22)`. `waitpid(pid=1, options=0)` -> `TypeError: posix.waitpid() takes no keyword arguments`; `wait(1)` -> `posix.wait() takes no arguments (1 given)`; `waitid` keywords -> `posix.waitid() takes no keyword arguments`; `wait3()` -> `wait3() missing required argument 'options' (pos 1)`.
- `waitpid` returns a plain tuple `(pid, status)`; a running child with `WNOHANG` -> `(0, 0)`; `waitid` with `WNOHANG` and nothing ready -> `None`; a second wait on a reaped pid -> ECHILD. Stop/continue: `WUNTRACED` -> status `0x137f`; `WCONTINUED` -> `0xffff`.
- `waitid` result: `posix.waitid_result(si_pid=..., si_uid=..., si_signo=17, si_status=4, si_code=1)`, length 5, `n_fields` 5; with `WNOWAIT` the child stays waitable; killed child -> `si_status` 9, `si_code` 2. `waitid(P_PIDFD, fd, WEXITED)` works.
- `wait3`/`wait4` -> a 3-tuple `(pid, status, rusage)`; `type(rusage) is resource.struct_rusage`, 16 fields, the first two floats and the rest ints, `repr` starts `resource.struct_rusage(ru_utime=`; with `WNOHANG` and nothing ready `(0, 0, <all-zero rusage>)`.
- A Python handler (SIGALRM from `setitimer`) runs while the main thread is in `waitpid`/`wait`/`waitid`, and the wait then returns the child's status; a handler that raises propagates.
kill / groups / pidfd:
- `kill(own pid, 0)` -> None; a missing pid -> `ProcessLookupError(3)`; pid 1 as non-root -> `PermissionError(1)`; signals 99, 65, -1 -> `OSError(22)`; a zombie can be signalled, a reaped pid is ESRCH; keywords -> `posix.kill() takes no keyword arguments`; an `IntEnum` signal is accepted; a handler for a signal sent to the own pid has run when `os.kill` returns.
- `killpg(missing, 0)` -> ESRCH; `killpg(own group, 99)` -> 22; `killpg(-5, 0)` -> 22; a child started with `setpgroup=0`/`process_group=0` is killed through `killpg(child pid, SIGTERM)` (exit code -15) and its group is then ESRCH.
- `getpgid(0) == getpgid(own pid) == getpgrp()`; `getpgid(pid=0)` accepted; `getsid(pid=0)` -> `posix.getsid() takes no keyword arguments`; a missing pid -> ESRCH for `getpgid`, `getsid`, `setpgid`; `setpgid(child, child)` after the child has exec'd -> `PermissionError(13)`; `getpgrp(1)` -> `posix.getpgrp() takes no arguments (1 given)`.
- `pidfd_open(child)`: a descriptor above 2, non-inheritable, blocking; `select.poll` reports `POLLIN` after the child exits; a reaped pid -> ESRCH; `-1` and unknown flags -> `OSError(22)`.
`os.posix_spawn` / `posix_spawnp` (texts carry the function's own name: `posix_spawnp: argv must not be empty`):
- Returns an int pid. `argv` may be a list or tuple of str/bytes/PathLike; `path` str/bytes/PathLike; `argv[0]` need not match the path. `env` is a mapping with str or bytes keys and values, `os.environ` itself, an empty dict (empty environment), or **`None` (the C environment: a raw `os.putenv` is visible)**.
- OS failures: `OSError` subclass by errno with `filename` = the path as passed (`b'/no/such/prog'` stays bytes; a PathLike gives its str): missing 2, not executable 13, directory 13, through a file 20, no `#!` 8, bad interpreter 2, `''` 2, a bare name with `posix_spawn` 2 (no PATH search). A failing file action or attribute is reported the same way, **with the program path as filename** (`DUP2` of 9999 -> `OSError(9, ..., '/bin/true')`; `OPEN` in a missing directory -> `FileNotFoundError(2, ..., '/bin/true')`; `setpgroup=1` -> `PermissionError(1, ..., '/bin/true')`). An action rejected when it is added (`(POSIX_SPAWN_CLOSE, -1)`) -> `OSError(9)` **without** filename.
- Argument errors, in CPython's check order (path and the type of `scheduler` first, then argv, env, file_actions, then the attributes in signature order): `posix_spawn: path should be string, bytes or os.PathLike, not int`; `ValueError: posix_spawn: embedded null character in path`; `TypeError: posix_spawn: scheduler must be a tuple or None`; `TypeError: posix_spawn: argv must be a tuple or list`; `ValueError: posix_spawn: argv must not be empty`; an argv item of a wrong type -> `TypeError: expected str, bytes or os.PathLike object, not int`; with NUL -> `ValueError: embedded null byte`; `ValueError: posix_spawn: argv first element cannot be empty`; env neither mapping nor None (`5`) -> `TypeError: posix_spawn: environment must be a mapping object or None`; a list or str as env -> `AttributeError: 'list' object has no attribute 'keys'`; an env key that is empty or has `=` after its first character -> `ValueError: illegal environment variable name` (`"=A"` is accepted); env value of a wrong type / NUL -> the two argv-item texts; `posix_spawn() takes exactly 3 positional arguments (N given)` (too few, too many, or `env=` by keyword); `posix_spawn() got an unexpected keyword argument 'bogus'`.
- `file_actions`: None or any sequence; not a sequence -> `TypeError: file_actions must be a sequence or None`; an element that is not a non-empty **tuple** (a list, an int, `()`) -> `TypeError: Each file_actions element must be a non-empty tuple`; unknown identifier -> `TypeError: Unknown file_actions identifier`; identifier or fd of a wrong type -> `'str' object cannot be interpreted as an integer`; wrong lengths -> `A close file_action tuple must have 2 elements`, `A dup2 file_action tuple must have 3 elements`, `A open file_action tuple must have 5 elements`. `(OPEN, fd, path, flags, mode)` accepts str/bytes/PathLike paths and creates the file with `mode`; actions run in order.
- Attributes: `setpgroup=None` none, an int -> that group; `resetids` any truthy value; **`setsid` truthy -> `NotImplementedError: posix_spawn: setsid unavailable on this platform`** (U3); `setsigmask`/`setsigdef`: an iterable of signal numbers, `ValueError: signal number 0 out of range [1; 64]` (also 65), a non-int item -> the integer TypeError, a non-iterable -> `'int' object is not iterable`; SIGKILL is accepted in both. **A `setsigmask` argument that is given, even `()` or `[]`, replaces the mask (measured: parent blocks SIGUSR1, `setsigmask=()` -> child `SigBlk` 0); when the keyword is absent the child inherits the parent's mask.** `setsigdef=()` changes nothing. `scheduler=None` none.
- `posix_spawnp` searches the **parent's** PATH (a change through `os.environ["PATH"]` is seen; the `env` argument's PATH is not used); relative PATH entries and relative program paths are resolved in the working directory.
- Inheritance: close-on-exec descriptors are closed in the child, inheritable ones stay open; `(DUP2, fd, fd)` keeps a close-on-exec descriptor.
`_posixsubprocess.fork_exec` called directly (errpipe record, wait status):
- success `b''`; every failure leaves a waitable pid with status 65280: missing `OSError:2:`, not executable or directory `OSError:d:`, through a file `OSError:14:`, no `#!` `OSError:8:`; lists per rule 1.7 step 5; an empty list `SubprocessError:0:`; cwd missing or `""` `OSError:2:noexec:chdir`, a file `OSError:14:noexec:chdir`, unsearchable `OSError:d:noexec:chdir`, bad cwd **and** missing program -> the chdir record; a bad stdio descriptor `OSError:9:noexec`; `pgid_to_set` 1 `OSError:1:noexec`.
- Argument checks: `TypeError: fork_exec expected 22 arguments, got 3` (23 on 3.13); `TypeError: fork_exec() argument 4 must be tuple, not list`; negative or unsorted `fds_to_keep` -> `ValueError: bad value(s) in fds_to_keep`; `errpipe_write` below 3 with `close_fds` -> `ValueError: errpipe_write must be >= 3`; an `args` item of a wrong type -> `TypeError: expected str, bytes or os.PathLike object, not int` (str items are accepted); an `executable_list` or `env_list` item that is str -> `TypeError: expected bytes, str found`; `executable_list` may be any sequence; `cwd` str or bytes.
- Descriptors with r (close-on-exec), w (in `fds_to_keep`), r2 (inheritable), w2 (close-on-exec): `close_fds=True` -> child sees `c o c c`; `close_fds=False` -> `c o o c`; `close_fds=False` without keeping -> `c c o c`; `close_fds=True` without keeping -> `c c c c`.
`subprocess` level: everything in the draft cases `subprocess_posix.py` and `subprocess_posix_errors.py` (their `.out` files are the measured reference), in particular: exit codes and `-15`/`-9`; the child's `$PPID` is `os.getpid()`; a redirected file shares the offset (`before`, child output, `after` in file order); a buffered reader's descriptor is at the kernel offset; `pass_fds` does not change the parent's flags; `start_new_session` -> `1 1`, `process_group=0` -> `0 1`; default children have `SigIgn` 0 and inherit a `pthread_sigmask` block; errors carry `filename` = the program as written (`'pyjvm-no-such-program-xyz'`, `'./relative-missing'`) or the `cwd`, and none for a bad stdio descriptor (`OSError(9, 'Bad file descriptor')`); `Popen([])` -> `IndexError`; a failed start leaves `returncode 255`, `_child_created True` (fork_exec path) or `returncode None`, `_child_created False` (the `close_fds=False` + absolute path case, which goes through `os.posix_spawn`), no children and no leaked descriptor.
Audit events: `os.posix_spawn` with `(path, argv, env)` for both functions (raised before the spawn, also when it fails); `os.kill` `(pid, sig)`; `os.killpg` `(pgid, sig)`; `_posixsubprocess.fork_exec` `(executable_list, args, env_list)` (3.8); none for the wait family, `pidfd_open`, `sysconf`, `confstr`, `getpgid`, `getsid`.

### 3.7 Which path `subprocess` takes on the reference interpreters (measured)
`subprocess._USE_POSIX_SPAWN` True, `_HAVE_POSIX_SPAWN_CLOSEFROM` **False**. So with the default `close_fds=True` every `Popen` goes through `fork_exec`; `os.posix_spawn` is used only for `close_fds=False` with a program path that has a directory part, no `cwd`, no `pass_fds`, no session/group/user arguments and stdio descriptors above 2. Both paths are covered by `subprocess_posix`.

### 3.8 Lane-dependent items
Every row was measured on all three lanes (no unknown lane this time).
| Item | 3.13 | 3.14 | 3.15 |
| --- | --- | --- | --- |
| `fork_exec` argument count (and the number in its TypeError) | 23 (`allow_vfork` last) | 22 | 22 |
| audit event `_posixsubprocess.fork_exec` | not raised | raised | raised |
| C-int argument of the wait/kill/group functions that does not fit 64 bits (either sign; added at ticket 3 review) | `OverflowError: Python int too large to convert to C long` | `OverflowError: Python int too large to convert to C int` | as 3.14 |
| `sysconf`/`confstr` argument (or table value) with only `__index__` (added at ticket 2 review) | TypeError `configuration names must be strings or integers` | accepted | accepted |
| out-of-range pid/int for `waitpid`, `wait4`, `waitid`, `kill`, `killpg`, `getpgid`, `getsid`, `setpgid`, `pidfd_open` | `OverflowError: signed integer is greater than maximum` | `OverflowError: Python int too large to convert to C int` | as 3.14 |
| wrong argument count for `waitpid`, `kill`, `killpg`, `waitid`, `setpgid` | `waitpid() takes exactly 2 arguments (1 given)` | `waitpid expected 2 arguments, got 1` | as 3.14 |
| `pidfd_open` `flags` (C unsigned int; added at ticket 5, measured): non-int / negative / above 32 bits / above 64 bits | `an integer is required` / `ValueError: value must be positive` / `Python int too large for C unsigned int` / `... to convert to C unsigned long` | `'<type>' object cannot be interpreted as an integer` / `ValueError: Cannot convert negative int` / `Python int too large for C unsigned int` (both) | as 3.14 |
| `Popen.wait(timeout=...)` uses `os.pidfd_open` + `select.poll` | no | no | yes (Python code; nothing to gate in the runtime) |
Gate the first four with `LangVersion.atLeast(3, 14)`. `wait3(2**40)`, the W* macros, `sysconf` and `confstr` use the "C int" text on every lane. Nothing else may be guessed: an item not in this table is lane-independent as measured.

## 4. Review checklist that applies to every ticket (Opus)
1. **Windows untouched.** No file named `Windows*` is in the diff. Shared files changed only as the ticket names; every such line is POSIX-only by construction (`IoFd.KERNEL`, a `POSIX_ONLY` module, or an added method nothing on Windows calls).
2. **Class loading.** No `PosixProc`, `PosixSpawn`, `PosixOsProc`, `PosixSubprocessModule`, `PosixConfNames`, `PosixStructSeq` reference is reachable on Windows; `test_windows_never_loads_posix_ffm` lists them and passes. No `Windows*` reference from the new classes.
3. **Every libc call checks its result.** errno only after a failing return and from the captured state of that call; the `posix_spawn*` family by return value, never errno.
4. **Native memory.** Everything handed to `posix_spawn` is native and from the call's confined Arena; `init` is paired with `destroy` in `finally`; pointer arrays are NULL-terminated; no segment outlives its Arena.
5. **No descriptor leak** on any path (temporaries, transient pidfd, pipes of tests); `adopt` after success, `forget` before close; nothing created without close-on-exec.
6. **No child leak and no stolen child.** A function that started a child either returns its pid to Python or reaps it; nothing in the runtime waits for "any child"; nothing signals a process the caller did not name.
7. **Signals.** 2.3 holds for every test and case in the diff. The wait rule of 1.5 is used for every blocking wait; `dispatchPending()` is never called while a native buffer holds a result not yet copied out.
8. **Environment.** `env=None`/`env_list=None` reach the child as libc `environ` read at call time; no use of `System.getenv`/`ProcessBuilder`.
9. **Emulated cwd.** Every spawn path adds `cwdAction()` before relative paths; error filenames are the caller's objects, never joined paths.
10. **No placeholders**: nothing returns a made-up value; an unsupported argument raises the text of 1.7; a function that cannot work is absent.
11. **Method size / data**: tables are arrays; `methodsize` 0 over 8000. The acceptance logs exist for both OSes and the counts in the report match them.

## 5. Tickets

### Ticket 0. Branch, measurement scripts in the repo, table generator, baseline (no runtime change)
Implementer tier: **sonnet**. Copying, one generator extension and gate orchestration with a failing-case inventory (not haiku: the baseline comparison must be right).
Precondition: B1 exists (2.1) and N0 ticket 13's gate results are in `work/n0/` or HANDOFF.
Do:
1. Create `n1-proc` from B1 (Windows repo and `~/pyjvm-n0`: `git fetch win && git checkout n1-proc`).
2. Commit under `work/n1/`: `measure/` = `m1.py`, `m2.py`, `m3.py`, `m4.py`, `layout.c`, `spawn.c`, `run.sh`, `runcase.sh` and the twelve `m*-31x.txt` outputs from `E:\pyjvm-work\n1\`; `cases/` = the nine draft cases and their `-31x-1.out` files from `E:\pyjvm-work\n1\cases\`. Do not edit them (they are this plan's evidence). Add SPDX headers only if `tests/test_licenses.py` covers `work/` (check; N0 ticket 0 hit this for `tools/`).
3. `tools/posix_measure_n1.py`: `m1.py`..`m4.py` concatenated into one script with the same output format, keeping the SAFETY comment of `m2.py` at the top (D4: generators and measurement scripts are committed). `tools/gen_posix_tables.py`: add a `--conf` mode that prints, from `os.sysconf_names` and `os.confstr_names` under Linux CPython, four Java array initialisers (names sorted, values parallel).
4. Rerun `tools/posix_measure_n1.py` on the three lanes and diff against the committed outputs (pids and pid-derived numbers differ; nothing else may). If the gates will run on the glibc 2.35 host, compile and run `layout.c` and `spawn.c` there and diff against section 3.2/3.5.
5. Baseline: copy N0 ticket 13's pass/fail/skip counts and failing-case names per lane and OS into `work/n1/baseline.md`. If N0 ticket 13 recorded only counts, rerun L-full/W-full at B1 (one gate at a time, under the lock).
Acceptance: branch exists on both clones; `work/n1/` committed; the measurement rerun shows no difference beyond pids; `baseline.md` lists failing cases by name.
Review checklist: nothing under `runtime/` or `tests/` changed; `tools/posix_measure_n1.py` contains no `killpg`/`kill` call that violates 2.3 (read every one); the generator's output for the three lanes is identical; the baseline is by case name and taken at B1 with a clean tree.

### Ticket 1. Spike S2 (scratch only, nothing committed except the report)
Implementer tier: **sonnet**. Scratch Java against a fixed list; nothing lands in the runtime.
Do, in `~/pyjvm-work/n1/spike/` (plain `javac`/`java --enable-native-access=ALL-UNNAMED`, Java 25, no pyJVM code; this runs a JVM, so take the run lock):
1. `posix_spawn` through handles built per 1.2 with file actions (`adddup2` of a pipe end onto 1, `addchdir_np` twice, `addclosefrom_np`) and attributes (`SETSIGMASK` empty, `SETSIGDEF`, `SETSID`); read the child's output through the pipe with `read(2)`; `waitpid` from a **different** Java thread than the spawner; exit status correct.
2. Failed spawn: missing program returns 2 and `waitpid(-1, WNOHANG)` is ECHILD; a script without `#!` returns 8 (proves `dlsym` resolved the default `posix_spawn` version).
3. `environ`: `setenv` a new variable through FFM, read `environ` again, spawn `/bin/sh -c 'echo $VAR'` with that pointer: the value arrives. With `System.getenv` it would not (show it).
4. What a JVM child inherits with **no** attributes and no actions, spawned from the main Java thread and from a second thread: `/proc/self/status` `SigBlk`, `SigIgn`, `SigCgt`; the descriptors open in the child (`ls -l /proc/self/fd`); `ulimit -n`; `env | sort` diffed against the JVM's own start environment. Then the same with `SETSIGMASK` empty. Report the masks as hex and as signal names. (Checks U1: SIGQUIT blocked without the attribute; SIGPIPE/SIGXFSZ not ignored; no descriptor above 2 unless the launcher passed one; the raised limit.)
5. JDK reaper: spawn `sleep 1` through FFM, sleep 3 s in Java without waiting, then `waitpid(pid, WNOHANG)` returns the pid and status 0 (the JDK did not reap it). Start `sleep 2` with `ProcessBuilder`, call FFM `waitpid(-1, 0)`: record what it returns and what `Process.waitFor()` then reports (expected: stolen, and `waitFor` gives 0).
6. `syscall(434, pid, 0)`: descriptor, `poll` 50 ms returns 0 while the child sleeps and 1 after it exits; `waitpid(getpid())` is ECHILD.
7. `sysconf` errno rule of 1.3: `__errno_location` write, `sysconf(32)` (`SC_SEM_NSEMS_MAX`, measured: no value) gives -1 with captured errno 0, `sysconf(41)` (`SC_EQUIV_CLASS_MAX`) and `sysconf(99999)` give -1 with 22, each 10 000 times in a loop with allocation between the two downcalls; count how often the "no value" case shows a non-zero errno.
8. `sun.misc.Signal.handle(new Signal("CHLD"), ...)`: accepted or `IllegalArgumentException`; if accepted, does the handler run when an FFM child exits (U8).
9. One `posix_spawn` of `/bin/true` + `waitpid` timed over 200 iterations, with and without 500 extra open descriptors and the `/proc/self/fd` listing.
Output: `work/n1/spike.md` with the numbers (the only file committed).
Acceptance: items 1-3, 5 (first half), 6 behave as stated. Items 4, 5 (second half), 7, 8, 9 are measurements: any result is recorded. **Stop and report to the planner** if: SIGPIPE or SIGXFSZ is *ignored* in the JVM (then `restore_signals` semantics change and gap 1.6(a) is wrong); the JVM passes inheritable descriptors above 2 of its own (then `close_fds=False` leaks them and 1.8 needs a rule); the JDK reaped the FFM child; the "no value" errno in item 7 is non-zero more than a handful of times (then 1.3 needs another rule).
Review checklist: the spike used the option combination of 1.2; item 4 was run from two threads; nothing but `spike.md` was added to the repo; 2.3 respected; the stop conditions were evaluated explicitly in the report.

### Ticket 2. `os.sysconf`, `os.confstr` and their name tables
Implementer tier: **sonnet**. A generated table and two functions with fully measured error rules.
Files: new `PosixConfNames.java` (generated), new `PosixProc.java` (first handles), new `PosixOsProc.java` (`install`), `PosixOsFds.java` (last line of `install`: `PosixOsProc.install(m);`), `tests/test_import_runtime.py` (add `PosixProc`, `PosixSpawn`, `PosixOsProc`, `PosixSubprocessModule`, `PosixConfNames`, `PosixStructSeq` to `POSIX_FFM_CLASSES`), new `tests/conformance/os_sysconf.py`.
Do:
1. `PosixConfNames`: `static final String[] SYSCONF_NAMES; static final int[] SYSCONF_VALUES;` and the `CONFSTR_` pair, from `tools/gen_posix_tables.py --conf`; header names the generator. No FFM import.
2. `PosixProc`: handles `SYSCONF`, `CONFSTR`, `ERRNO_LOCATION` (3.1); `static long sysconf(int name)` returning the value, -1 for "no value", or throwing `osError(22, null)` per 1.3; `static String confstr(int name)` returning the text, null for "no value", or throwing: first call with a 256-byte buffer, a larger result -> second call with the returned size; decode as UTF-8 with surrogateescape without the NUL.
3. `PosixOsProc.install(m)`: `sysconf_names` and `confstr_names` as new `PyDict`s (name -> int, built once per module creation); `sysconf(name)`, `confstr(name)`: argument rules of 3.6 (str -> table lookup; `Boolean`/int -> `cInt` with the all-lane "C int" text; anything else -> the TypeError).
4. `tests/conformance/os_sysconf.py` = `work/n1/cases/os_sysconf.py` unchanged.
CPython reference: 3.6 "sysconf / confstr"; 3.3.
Acceptance: `os_sysconf` on Linux three lanes (it ends by importing `subprocess` and printing `_USE_POSIX_SPAWN`: this is False until ticket 4 adds `os.posix_spawn`, so **until ticket 4 the case is expected to differ in its last line only**; say so in the report and show the diff); L-min; W-min plus `os_sysconf` on Windows (first line `nt False False False False`); the Windows class-loading test passes with the longer list.
Review checklist: the tables are generated, not typed (rerun the generator and diff); `os.sysconf_names` is a real dict that user code can mutate without changing lookups in the runtime (CPython looks names up in its own C table: mutate-and-call line added to the scratch check, not the case); the errno rule is exactly 1.3 (zero through `__errno_location`, two attempts); `confstr` handles a result longer than the first buffer; `PosixOsFds.install` gained exactly one line; no handle of a later ticket is declared yet (a missing symbol would fail class initialisation for everything).

### Ticket 3. Wait family, W* macros, `kill`, `killpg`, process-group functions
Implementer tier: **sonnet**. One or two syscalls per function with measured semantics; the signal interplay is ticket 5.
Files: `PosixProc.java`, `PosixOsProc.java`, new `PosixStructSeq.java`, new `tests/conformance/os_wait_kill.py`.
Do:
1. `PosixStructSeq.define(String module, String name, String[] fields)` -> `PyClass`, and `make(PyClass, Object... values)`: the technique of `SysExtras.seqClass`/`seq` (`SysExtras.java:27-52`: tuple base, payload `PyTuple`, `n_sequence_fields`/`n_fields`/`n_unnamed_fields`, `__match_args__`, fields as attributes) with the module in the repr (`posix.waitid_result(si_pid=1, ...)`). Do not edit `SysExtras`. Constructing one from Python (`os.waitid_result((...))`) is not supported (raises TypeError from the default constructor); recorded gap.
2. `PosixProc`: handles and raw wrappers returning the result or `-errno`: `waitpid(pid, options)` -> `long` packing pid and status (or two-element `int[]`), `waitid(idtype, id, options, MemorySegment info)`, `kill`, `killpg`, `getpgid`, `getsid`, `getpgrp`, `setpgid`, `getpid`. In this ticket a blocking wait is one call with the EINTR retry of N0 1.6 item 2 (`dispatchPending()` then retry); the main-thread rule comes in ticket 5 and must not be half-implemented here.
3. `PosixOsProc.install` registers, with the argument rules of 3.4/3.6/3.8:
   - constants of 3.3 (wait, `P_*`, `CLD_*`, `EX_*`); `waitid_result` class;
   - `WIFEXITED`, `WEXITSTATUS`, `WIFSIGNALED`, `WTERMSIG`, `WIFSTOPPED`, `WSTOPSIG`, `WIFCONTINUED` (keyword `status` allowed), `WCOREDUMP` (positional only), `waitstatus_to_exitcode(status)`: on the C-int value, formulas of 3.2, texts of 3.6;
   - `waitpid(pid, options)`, `wait()` (= `waitpid(-1, 0)`), `waitid(idtype, id, options)` (`None` when `si_pid == 0`);
   - `kill(pid, signal)`: `pid == PosixProc.getpid()` -> `SignalModule.kill(pid, sig)` (the existing in-process path, unchanged); otherwise libc `kill`; `killpg(pgid, signal)`: libc, after raising the audit event `os.killpg` when `SysExtras.auditing()`. (`os.kill`'s event comes from the existing `AuditEvents` wrap, which runs after this registration: verify it still fires once.)
   - `getpgid(pid)` (keyword allowed), `getpgrp()`, `getsid(pid)`, `setpgid(pid, pgrp)`.
4. `tests/conformance/os_wait_kill.py` = the draft unchanged. It creates children with `os.posix_spawn`, so it **cannot pass before ticket 4**; this ticket's own check is `work/n1/t3_check.py` (written by the implementer: every line of the draft above the first `spawn(` call, i.e. the constants, the macro table, the no-children errors and the lane-gated block, plus the `kill`/`killpg`/`getpgid`/`getsid`/`setpgid` lines that need no child), run under CPython and pyJVM on the three lanes and diffed.
CPython reference: 3.6 "W* macros", "wait family", "kill / groups"; 3.8.
Acceptance: `t3_check.py` output identical to CPython on three lanes; `signal_basic`, `signal_blocking_io` (they use `os.kill` on the own pid), `os_sysconf` and L-min on three lanes; W-min plus `os_wait_kill` on Windows (prints `nt`); in `PYJVM_POSIX_FDS=nio` mode `os.waitpid(-1, 0)` still raises ChildProcessError from the old lambda (one line in `tests/test_posix_fds.py`).
Review checklist:
- `SignalModule.java` is not in the diff; own-pid `kill` goes through it; no `ProcessBuilder` is reachable from `os.kill` in kernel mode;
- the signal argument is truncated to 32 bits, the pid argument is range-checked with the lane's text;
- status and `siginfo` buffers are per call (thread-local or confined), zeroed before `waitid`, read only after a successful return; `si_uid` unsigned;
- `waitid` returning 0 with `si_pid == 0` is `None`, not a result object;
- `wait()` and `waitpid(-1, ...)` are the only "any child" waits, called only on the user's request (checklist item 6);
- the W* macros match the table of 3.6 for negative and above-16-bit values (C int semantics, not Python big-int arithmetic);
- 2.3 for `t3_check.py` and the case: read every `kill`/`killpg` line;
- the Windows `nt` module still gets the old W* lambdas and `waitpid` (W-min `os_platform`, `subprocess_windows` unchanged).

### Ticket 4. The spawn core and `os.posix_spawn` / `os.posix_spawnp`
Implementer tier: **opus**. Native pointer arrays and opaque libc objects shared with a `vfork`-style child, a long list of exact argument errors, the emulated cwd and the environment rule; a mistake is memory corruption or a child with the wrong descriptors.
Files: new `PosixSpawn.java`; `PosixNative.java` (`symbol`); `SignalModule.java` (add `static long blockedMask()`; nothing else); `PosixOsProc.java`; new `tests/conformance/os_posix_spawn.py`; `tests/conformance/os_wait_kill.py` now runs.
Do:
1. `PosixSpawn` per 1.2 and 1.4 (`Plan`, `spawn`, `cwdAction`), with the glibc check of 3.1.
2. `SignalModule.blockedMask()`: `synchronized (LOCK)`, bits `1L << (i - 1)` for `blocked[i]`, `i` in 1..64.
3. `PosixOsProc`: `POSIX_SPAWN_OPEN/CLOSE/DUP2` constants; `posix_spawn` and `posix_spawnp` sharing one implementation parameterised by name and `usePath`:
   - argument binding and every error of 3.6 in the stated order;
   - `path`: `os.fspath` rules; keep the original object for the error's `filename` (a PathLike's `__fspath__` result); bytes for the call via UTF-8 surrogateescape;
   - `env`: `None` -> `envp = null`; a mapping: call `keys()` and `values()` (so a list gives CPython's AttributeError), encode `key + "=" + value`;
   - actions: `cwdAction()` first if not null, then the caller's in order;
   - attributes: `setpgroup`, `resetids`, `setsid` (the NotImplementedError of 3.6), `setsigmask` (keyword given -> that set; absent -> `SignalModule.blockedMask()`; `SETSIGMASK` always), `setsigdef`, `scheduler`: `None` -> nothing; a tuple -> `NotImplementedError("posix_spawn: scheduler is not supported by pyJVM")` (there is no `os.sched_param`; recorded gap);
   - audit event `os.posix_spawn` `(path, argv, env)` after the arguments are parsed, before the spawn;
   - result: the pid; `-errno` -> `PosixNative.osError(errno, null)` with `filename` set to the original path object the way `PosixFds.withFilename` does, or without filename when `plan.failedAtAdd`.
4. `tests/conformance/os_posix_spawn.py` = the draft unchanged.
CPython reference: 3.5, 3.6 "`os.posix_spawn`", 3.4.
Acceptance: `os_posix_spawn`, `os_wait_kill`, `os_sysconf` (now fully equal: `_USE_POSIX_SPAWN` True) on Linux three lanes, `os_posix_spawn` five times on 3.15; `signal_basic`, `signal_blocking_io`, `os_pipe`, `os_fd_flags` and L-min; a pytest `tests/test_posix_process.py` (new, skip unless Linux; launch helper as `tests/test_posix_fds.py`): (a) after `os.chdir(tmp)`, `os.posix_spawn("/bin/pwd", ...)` output equals `tmp` and equals CPython's; (b) `os.putenv` then `posix_spawn(..., None)`: the child sees it; (c) 200 spawn+wait rounds leave the descriptor count unchanged (`len(os.listdir("/proc/self/fd"))`) and `os.waitpid(-1, os.WNOHANG)` raises ChildProcessError; (d) under `PYJVM_POSIX_FDS=nio`, `hasattr(os, "posix_spawn")` is False. W-min plus the two new cases on Windows.
Review checklist:
- checklist items 3, 4, 5, 8, 9 line by line: `init`/`destroy` pairing on every exit including an exception thrown while encoding arguments; the Arena is confined and closed after `destroy`; arrays have `n + 1` slots with a NULL last; the pid cell is read only when the return is 0;
- no `posix_spawn_file_actions_t` or attribute is reused across attempts or threads;
- `environ` is read inside `spawn`, not cached in a static;
- `SETSIGMASK` is unconditional; the mask comes from `blockedMask()` unless the keyword was given (an empty tuple given explicitly is "given": the case line `sigmask{}` and `m4` anchor); bits are `sig - 1`;
- the emulated-cwd action precedes the user's actions; the case lines `cwd`, `cwdrel`, `cwdopen`, `cwdp` pass, and with no `os.chdir` there is **no** chdir action (the JVM's real cwd is used: a deleted start directory must not break spawning);
- error filename is the caller's object: bytes stay bytes (`missingb`), no joined path ever appears;
- `failedAtAdd` errors have no filename (`faC-1`);
- `setsid=True` raises (matches the reference; Q3) and nothing sets `POSIX_SPAWN_SETSID` from `os.posix_spawn`;
- `SignalModule` diff is one added method;
- no child is leaked by the tests; 2.3.

### Ticket 5. Blocking waits and signals (D5), `os.pidfd_open`
Implementer tier: **opus**. Timing- and signal-dependent logic on the main thread; failures are hangs, lost statuses or 50 ms added to every `subprocess.run`.
Files: `PosixProc.java`, `PosixOsProc.java`, new `tests/conformance/os_wait_signals.py`.
Do:
1. `PosixProc.pidfdOpen(int pid, int flags)`: `syscall(434, pid, flags)`; result or `-errno`.
2. `os.pidfd_open(pid, flags=0)` (keywords allowed): on success `PosixFds.adopt(fd)` and return it; error -> `osError(errno, null)`.
3. The wait function of 1.5, exactly as written, used by `waitpid`, `wait`, `waitid` (and later `wait3`/`wait4`). Classification of "one process, exit only": `waitpid`/`wait4` with `pid > 0` and `(options & (WUNTRACED | WCONTINUED)) == 0`; `waitid` with `P_PID` (or `P_PIDFD`, using the caller's descriptor) and `(options & (WSTOPPED | WCONTINUED)) == 0`. Everything else uses the `SignalModule.sleep` back-off (1 ms doubling to 50 ms).
4. `tests/conformance/os_wait_signals.py` = the draft unchanged.
CPython reference: 3.6 "wait family" last item, "kill / groups / pidfd"; 3.5 last item.
Acceptance: `os_wait_signals` on Linux three lanes and five more times on 3.15 (timing lines); `os_wait_kill`, `os_posix_spawn`, `signal_blocking_io`, `select_pipes` and L-min; `tests/test_posix_process.py` additions: (e) a second thread blocked in `os.waitpid(child, 0)` while the main thread spawns and waits for 50 other children: all statuses correct; (f) descriptor count unchanged after 200 main-thread waits (the transient pidfd is closed); (g) main thread in `os.wait()` with two children exiting 0.2 s apart returns the first one's pid within 0.15 s of its exit. W-min plus the case on Windows.
Review checklist:
- the first step of a main-thread blocking wait is a `WNOHANG` call, so an already-exited child costs no `poll`;
- the pidfd is opened once per wait call, closed in `finally` on every path including a raising handler, and never when it is the caller's (`P_PIDFD`);
- `pidfd_open` failure of any kind falls back to the back-off, silently;
- a wait with `WNOHANG` from Python is one syscall, never a loop;
- off the main thread and before `_signal` is imported there is exactly one blocking call (plus EINTR retries);
- stop/continue waits never use the pidfd (it does not become readable for a stop);
- nothing is dispatched between a successful wait call and building its result;
- the timing line of the case (mean below 40 ms) would fail with a fixed 50 ms sleep: ask the implementer to show it failing with the pidfd path disabled, then restored;
- 2.3.

### Ticket 6. `_posixsubprocess.fork_exec`: the success path
Implementer tier: **opus**. Descriptor plumbing between two processes where a single wrong action deadlocks `subprocess` (a leaked pipe end) or silently redirects output.
Files: new `PosixSubprocessModule.java`; `ImportSupport.java` (the `_posixsubprocess` case becomes `IoFd.KERNEL ? PosixSubprocessModule.create() : StubModules.posixsubprocess()`); `PosixFds.java` (`dupAbove`, `listFds` extracted from `closeRange`); new `tests/conformance/subprocess_posix.py`; `tests/test_posix_process.py`.
Do:
1. Module `_posixsubprocess` with one function `fork_exec`. Argument count per lane (3.8) with the measured TypeError; bind the 22 names of 1.7; the 23rd is ignored.
2. The unsupported-argument errors of 1.7 (all five), before anything is created.
3. Steps 1-5 of 1.7. On total failure in **this** ticket: write `OSError:<hex errno>:` (no classification yet) and return `PosixProc.getpid()`. Ticket 7 replaces exactly this branch.
4. `subprocess_posix.py` = the draft unchanged.
5. pytest additions (pyJVM-specific, compared with fixed texts, not with CPython): `subprocess.run(["true"], preexec_fn=lambda: None)` -> the RuntimeError text; `umask=0o77`, `user=os.getuid()`, `group=os.getgid()`, `extra_groups=[]` -> the four NotImplementedError texts; after each, the descriptor count is unchanged and no child exists.
CPython reference: 3.6 "`subprocess` level", 3.7; 1.7.
Acceptance: `subprocess_posix` on Linux three lanes (cold cache: 8-13 minutes for the first lane run), then three more times on 3.15; the pytest; `os_putenv_child` (still first line only), `os_pipe`, `signal_blocking_io`, P-min so far and L-min; W-min including `subprocess_windows` and `asyncio_subprocess_pipes` on the three Windows lanes (the import switch line is shared).
Review checklist:
- **`errpipe_write` is not in any `DUP2` action** and not in `K`; the case would hang on its first `subprocess.run` otherwise: confirm by reading, not only by the test passing;
- action order is 1.7 step 2 exactly; the stdio rules handle `p2cread == 0`, `c2pwrite == 1`, `errwrite == 2` with `DUP2(fd, fd)` (close-on-exec cleared), and `c2pwrite == 0`/`errwrite in (0, 1)` through `dupAbove`; the case lines "stdio descriptor shuffles" pass;
- temporaries from `dupAbove` are closed in `finally` on success, failure and exception;
- `close_fds=True`: `CLOSE` only for listed descriptors below `maxK` outside `K`, then `CLOSEFROM(maxK + 1)`; `close_fds=False`: no close action at all; the four-descriptor probes of the case print `cccc`, `cocc`, `ccoc`, `occo`;
- the listing of `/proc/self/fd` is taken after the temporaries are created and never used to close anything in the parent;
- `env_list=None` -> libc environ (case lines `[inherited]`/`[unset]`); `env_list=[]` -> an empty environment, not environ;
- `cwdAction()` then the user's `cwd`, relative `cwd` resolved by the second chdir (case lines after `os.chdir(d)`);
- `restore_signals`, `call_setsid`, `pgid_to_set` map to the three flags; `SETSIGMASK` always;
- `executable_list` loop follows the errno rule even though classification is ticket 7;
- the import switch: Windows never reaches the new class (the `POSIX_ONLY` test in `createNative` throws first); `nio` mode still gets the shell;
- no `ProcessBuilder`, no `System.getenv`; 2.3 for the case (it signals only its own children; `killpg(p.pid, ...)` only for a child created with `process_group=0`).

### Ticket 7. `fork_exec` failures: the errpipe record, the stand-in child, CPython's argument checks, audit event
Implementer tier: **opus**. Error classification from one errno with parent-side diagnosis, and a second spawn on the failure path; wrong classification gives a wrong exception type or filename in the most common error users see.
Files: `PosixSubprocessModule.java`, `PosixProc.java` (`faccessat`), new `tests/conformance/subprocess_posix_errors.py`, new `tests/conformance/posixsubprocess_errpipe.py`, `tests/test_posix_process.py`.
Do:
1. Steps 6 and 7 of 1.7, replacing ticket 6's interim branch. The diagnosis uses `PosixFds`' `fstatat` (raw, no exception) and `faccessat`; paths through `PosixFds.pathBytes` rules (the emulated cwd joined for a relative `cwd`).
2. CPython's argument checks of 3.6 "`fork_exec` called directly", raised before the unsupported-argument errors: tuple check of argument 4, `bad value(s) in fds_to_keep` (negative, unsorted or duplicate), `errpipe_write must be >= 3` when `close_fds`, item types of `args`, `executable_list`, `env_list`.
3. Audit event `_posixsubprocess.fork_exec` `(executable_list, args, env_list)` on 3.14+ (3.8), after the argument checks.
4. The two cases = the drafts unchanged.
CPython reference: 3.6 "`fork_exec` called directly" and "`subprocess` level"; 1.7 steps 6-7.
Acceptance: `subprocess_posix_errors`, `posixsubprocess_errpipe`, `subprocess_posix` on Linux three lanes; pytest additions: (h) after 100 failing `subprocess.run(["/no/such"])` calls, `os.waitpid(-1, os.WNOHANG)` raises ChildProcessError and the descriptor count is unchanged (every stand-in was reaped by `subprocess`); the own-pid fallback has no test hook and is checked by reading; (i) an audit hook sees the events of 3.6 for `subprocess.run(["true"])` per lane. P-min and L-min; W-min plus the two cases on Windows.
Review checklist:
- the record bytes are exactly `OSError:<lowercase hex, no prefix>:<suffix>`; classification order is EBADF, chdir diagnosis, group errors, exec;
- the chdir diagnosis runs only after a failure and only when the plan has a chdir action; it checks the emulated cwd before the user's `cwd`; ENOTDIR for a non-directory comes from the `S_IFMT` test, not from `faccessat` (which says 13: measured);
- the stand-in is spawned only after every candidate failed, with `CLOSEFROM(3)` and an empty mask, and its pid is the return value; when it cannot be spawned the function returns the own pid and does **not** raise;
- the write to `errpipe_write` happens before the stand-in is started (so `subprocess` cannot read EOF first) and its result is checked;
- no path leaves a child that nobody will wait for (the stand-in is always returned);
- argument-check order: a call that is wrong in a CPython-checked way and also passes `preexec_fn` reports the CPython error;
- `posixsubprocess_errpipe` lines `list1`-`list4` (the errno rule) and `cwd+missing` pass on three lanes;
- the fork_exec audit event is absent on 3.13.

### Ticket 8. `os.wait3`, `os.wait4`, `resource.struct_rusage`
Implementer tier: **sonnet**. One more syscall on the ticket 5 wait function and a struct reader with measured offsets.
Files: `PosixProc.java`, `PosixOsProc.java`, `StubModules.java` (`resource()`: one line `if (IoFd.KERNEL) m.attrs.put("struct_rusage", PosixOsProc.rusageClass());`), new `tests/conformance/os_wait_rusage.py`.
Do: `PosixProc.wait4(pid, options, MemorySegment rusage)` (144-byte buffer, zeroed per call) through the 1.5 wait function; `PosixOsProc.rusageClass()` = `PosixStructSeq.define("resource", "struct_rusage", <the 16 names of 3.2 in order>)`, created once; `rusage(MemorySegment)` -> instance with `ru_utime`/`ru_stime` as `double` `sec + usec / 1e6` and fourteen `long`s (N2's `resource.getrusage` will call this same method: keep it package-private and free of wait logic); `wait3(options)`, `wait4(pid, options)` (keywords allowed), returning a tuple `(pid, status, rusage)`; nothing ready with `WNOHANG` -> `(0, 0, <all-zero rusage>)`. The rest of the `resource` shell (its fake `getrlimit`/`setrlimit`/`getpagesize`) is N2's and is not touched. The case = the draft unchanged.
CPython reference: 3.6 "wait family"; 3.2.
Acceptance: `os_wait_rusage` on Linux three lanes; `os_wait_kill`, `os_wait_signals`; L-min; W-min plus the case on Windows.
Review checklist: `type(os.wait3(0)[2]) is resource.struct_rusage` (one class object, not two); offsets per 3.2; the buffer is zeroed so the `WNOHANG` case really is all zero; `wait3` is `wait4(-1, ...)` and therefore an "any child" wait only on the user's request; `StubModules` gained exactly one line and `resource` is still absent on Windows; main-thread signal handling comes from the shared wait function, not a copy.

### Ticket 9. `os_putenv_child` stage 2 (the POSIX branch)
Implementer tier: **haiku**. A test-file edit from a verified draft and three runs; any failure is a stop-and-report, not a fix.
Files: `tests/conformance/os_putenv_child.py` only.
Do: keep the Windows branch byte-identical; change the header comment (POSIX is no longer absent); add `elif os.name == "posix":` with the body of `work/n1/cases/os_putenv_child_posix_draft.py` below its `if os.name == "posix":` line (checks 1-10 and 12 of `putenv_child_plan.md` section 4; the child is `/bin/sh -c 'printf "[%s]" "${NAME-<unset>}"'`, every `subprocess.run` has `timeout=60`). Then `putenv_child_plan.md` step 6: rerun the Windows lanes because the file changed.
CPython reference: the draft's `.out` files (measured, three lanes identical).
Acceptance: `os_putenv_child` on Linux three lanes and on Windows three lanes; `os_putenv` unchanged.
Review checklist: `git diff` of the Windows branch is empty apart from `if` -> unchanged and the added branch; the negative control of `putenv_child_plan.md` section 7 was done once on Linux by the reviewer's Haiku run (a variable name the child does not read makes the case fail); if the case fails under pyJVM the implementer reported and did not touch the runtime (the likely spot would be `envp` not being libc `environ`: ticket 6 checklist).

### Ticket 10 (optional; owner question Q2). `os.system`
Implementer tier: **sonnet**. A thin composition of ticket 4 and ticket 5.
Files: `PosixOsProc.java`, `tests/conformance/subprocess_posix.py` (three lines at the end), `tests/test_posix_process.py`.
Do: `system(command)` (keyword allowed; str/bytes/PathLike, NUL -> `ValueError: embedded null byte`): audit event `os.system` `(command,)`; spawn `/bin/sh` with argv `["sh", "-c", command]`, libc environ, `cwdAction()`, no close actions (inheritance as the flags say, like `system(3)`), `SETSIGMASK` with `blockedMask()`; wait with the 1.5 function; return the wait status; a spawn failure returns `127 << 8` (what `system(3)` reports when the shell cannot run). Not reproduced, recorded: `system(3)` ignores SIGINT/SIGQUIT in the parent and blocks SIGCHLD while waiting. Case lines: `os.system("exit 3")` -> 768; `os.system("kill -TERM $$")` -> 15; `os.waitstatus_to_exitcode(os.system("true"))` -> 0. Before coding, measure these three lines and the argument errors on the three lanes with a scratch script and add them to `work/n1/measure/` (they are **not** measured in this plan beyond `os.system("exit 3") == 768`).
Acceptance: `subprocess_posix` on three lanes; pytest: output of `os.system("echo hi")` appears on the inherited stdout after a flush.
Review checklist: no `system(3)` libc call (it would change the JVM's signal dispositions process-wide); the child is always reaped; 2.3.

### Ticket 11. CPython's own tests, performance, triage
Implementer tier: **opus**. Open-ended diagnosis; a wrong "blocked" label hides a real N1 bug.
No design change expected; fixes only inside the scope of tickets 2-10, each its own small commit with a conformance line that fails before the fix. Anything larger is listed, not fixed.
Run on Linux, 3.15 lane first, through `tools/run_cpython_test.py`: `test_subprocess` classes `ProcessTestCase`, `POSIXProcessTestCase`, `RunFuncTestCase`, `MiscTests`, `ContextManagerTests`; `test_posix` `TestPosixSpawn`, `TestPosixSpawnP`; `test_os` `PidTests`, `SpawnTests` (expected absent), `test_wait3`, `test_wait4`, `test_popen`; `test_signal` `PendingSignalsTests` subset that uses `os.kill`. Most `test_subprocess` tests start `sys.executable`, which is not runnable under pyJVM (U7): record them as "blocked: needs a runnable sys.executable" with the count, and report the count to the owner (Q4). Others need `fork` (D2), `preexec_fn`/`user`/`group`/`umask` (1.7), sockets (N3), `pty`/`termios` (N2).
Output: `work/n1/cpython_tests.md`: per module passed/failed/errors/skipped, and for every failure one line: cause and one of {fixed here, blocked: sys.executable, blocked: fork, blocked: spawn helper, blocked on N2, blocked on N3, out of scope with reason}.
Performance: (a) 200 x `subprocess.run(["/bin/true"])` under CPython and pyJVM (warm), mean per call; (b) the same with 500 extra descriptors open in the parent; (c) `subprocess.run(["cat"], input=64 MiB, capture_output=True)` wall time.
Acceptance: the report exists; nothing unclassified; (a) under pyJVM at most 3x CPython's mean or the numbers are reported to the planner (the stop line is a report, not a redesign by the implementer).
Review checklist: every "fixed here" has a conformance line; "blocked" labels are plausible (the test really calls what the label names); no fix touches shared code; timings sequential on one machine state; 2.3 for every scratch script.

### Ticket 12. Documentation and final gates
Implementer tier: **sonnet**. Accurate docs from this plan and the tickets' results, then the full gates (not haiku: the limits must be stated correctly).
Files: `JVM_BACKEND.md` ("POSIX native layer": new subsection "Processes": `posix_spawn` and no fork; what `preexec_fn`/`user`/`group`/`extra_groups`/`umask` raise; the error-record protocol and the stand-in child; the wait rule and its latencies; `waitpid(-1)` and JDK children; signals in children (mask from `pthread_sigmask`, gap 1.6(a)); raised `RLIMIT_NOFILE`; the emulated cwd and its removed-directory limit; the absent functions of 3.4; the 2.3 warning about `killpg(1, ...)`), `HANDOFF.md` (work-log entry; open item 12 done; "Known Windows gaps" unchanged), `STDLIB_STATUS.md` (regenerate on Linux: the 8 `_posixsubprocess` rows), `work/native_modules_plan.md` (N1 status, pointer here, obligations for N2/N3: `PosixStructSeq`, `PosixOsProc.rusage`, the wait function, `adopt`), `work/putenv_child_plan.md` (stage 2 done), class comments of `StubModules` (no longer mentions `_posixsubprocess` as a shell in kernel mode), `PosixFiles` ("Process creation ... not implemented" is no longer true on POSIX kernel mode), `SignalModule.kill` comment.
Then L-full and W-full; compare with `work/n1/baseline.md`.
Acceptance: Linux: every case that passed at baseline passes, the failing set is the baseline's or smaller, and the new cases (`os_sysconf`, `os_posix_spawn`, `os_wait_kill`, `os_wait_signals`, `subprocess_posix`, `subprocess_posix_errors`, `posixsubprocess_errpipe`, `os_wait_rusage`, `os_putenv_child`) pass on three lanes. Windows: identical pass/fail sets to baseline on three lanes plus the new cases passing (first line only); pytest and methodsize unchanged. After this ticket the owner may release N0 ticket 14.
Review checklist: no doc claims something the tests do not show; the limits list is complete (1.4 last bullet, 1.5 latencies, 1.6 a-c, 1.7 unsupported arguments, U5, U7); Windows results equal baseline by case name; comment-only changes in shared Java files do not alter code.

## 6. Risks
1. **No JVM-side fact was measured** (U1). Mitigation: ticket 1 with explicit stop conditions before any runtime code depends on them.
2. **A leaked pipe end deadlocks `subprocess`** (the errpipe write end, a `dupAbove` temporary, a transient pidfd). Mitigation: checklist item 5, the hang-if-wrong lines of `subprocess_posix`, descriptor-count pytests in tickets 4-7. The runner has no per-case timeout: a hang blocks the lane, so tickets 6 and 7 run the case alone first.
3. **Failure classification is a heuristic** (1.7 step 6): the same errno can come from an action or from exec, and the parent-side diagnosis races with the file system. Exact for the measured cases; anything else falls back to "exec failed", which names the program.
4. **The stand-in child** is unusual (Q1). It is confined to the failure path and has a tested fallback.
5. **Signals**: the test files of this phase send real signals; one wrong pid argument kills unrelated processes (it happened while planning). Mitigation: 2.3, reviewed by reading.
6. **`waitpid(-1)` and JDK children** (1.6): documented, unguarded.
7. **Reference-interpreter build quirks** (U3) are baked into the cases (`POSIX_SPAWN_CLOSEFROM` absent, `setsid` NotImplementedError). A change of reference build changes expected outputs on both sides at once, so the cases keep passing; the runtime's constants would then need Q3 revisited.
8. **Shared code**: three shared files get one line or one method each; Windows is covered by W-min at every ticket and the class-loading test.
9. **Cold caches**: the first `subprocess` import per lane and cache costs 8-13 minutes; tickets 6, 7, 9 pay it once per lane.

## 7. Notes filled in by tickets 0 and 1
(empty: ticket 0 adds the rerun diff and, if used, the glibc 2.35 results; ticket 1 adds the spike summary and the answers to U1, U8.)

## 8. Questions for the owner
- Q1. **The pid `fork_exec` returns after a failed start.** This plan starts a real stand-in child that exits 255 (identical `returncode`, no ResourceWarning; costs one `/bin/sh` on failure paths only). Alternative: return the process's own pid and let `subprocess` hit ECHILD (simpler; the discarded `Popen` object then has `returncode None` and warns in `__del__` when warnings are on). Recommended: the stand-in. Choosing the alternative removes step 7's first half and the "failed Popen object" block at the end of `subprocess_posix_errors.py`, and changes the wait statuses printed by `posixsubprocess_errpipe.py` (they would need to be dropped from its output).
- Q2. **`os.system`** (ticket 10) is not in the parent plan's N1 list. Include it? Recommended: yes (small, common, and absent today).
- Q3. **Match the uv reference build or glibc's capabilities?** `os.POSIX_SPAWN_CLOSEFROM` and `posix_spawn(setsid=True)` work on this glibc but are absent/NotImplemented in the reference interpreters. This plan matches the reference (so `hasattr` and the error agree with the lane's CPython). Recommended: keep matching.
- Q4. **`sys.executable`** is `"pyjvm"`, so code that re-runs the interpreter (`[sys.executable, "-c", ...]`, most of `test_subprocess`, `multiprocessing` spawn) fails with FileNotFoundError. A launcher script on PATH would be a separate work item; ticket 11 only counts the blocked tests. Wanted, and when?
- Q5. **Branch**: `n1-proc` from the N0 ticket 13 commit (2.1), or continue on `n0-fds`? Recommended: a new branch, so N0 ticket 14 can be applied to either.
