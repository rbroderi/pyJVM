# N1 ticket 1: spike S2 (FFM process creation in a JVM)

Measured 2026-11-24 in WSL Ubuntu 24.04 (glibc 2.39, kernel 6.6), Java 25.0.5.1 (`~/jdk-25`), `java -Xmx256m --enable-native-access=ALL-UNNAMED`, uid 1000, under the run lock. Scratch sources (not in the repo): `E:\pyjvm-work\n1\spike\` (`L.java` the FFM layer, `Spike.java` the items, `run.sh`/`run2.sh` runners, `run1.log`..`run3.log` raw output). No pyJVM code was used. Handles follow plan 1.2: `fn` = errno capture (call state first), `fnNoCapture` for the `posix_spawn*` family and its file-action/attribute functions and `getpid`/`__errno_location`, `firstVariadicArg` for `open` (2) and `syscall` (1), one confined `Arena` per spawn, thread-local call state, no `critical`. Only children the spike spawned were ever signalled-for or waited-for (no signal was sent to anything; items 5/5b waited on pids it created).

## Results per item

1. **posix_spawn with actions and attributes: as stated.** `/bin/sh -c ...` spawned from `main` with `adddup2(pipeW,1)` (+ onto 2), `addchdir_np("/tmp")` then `addchdir_np("/")`, `addclosefrom_np(3)`, flags `SETSIGMASK|SETSIGDEF|SETSID` (sigdefault SIGPIPE+SIGXFSZ, empty sigmask). Output read with `read(2)`: cwd `/` (second chdir wins), `sid == pid` (new session), child fds `0 1 2` only. `waitpid` ran on a different Java thread (`waiter-thread`, spawner was `main`) and returned the pid with status 1792 = exit code 7.
2. **Failed spawn: as stated.** `/nonexistent/prog` -> `posix_spawn` returns 2; `waitpid(-1, WNOHANG)` -> -1, errno 10 (ECHILD). `posix_spawnp` of a missing name -> 2. Executable text script without `#!` -> returns 8 (ENOEXEC), so `dlsym` resolved the default `posix_spawn` version (the version number itself was not logged); no child left (ECHILD again).
3. **environ: as stated, with one correction.** `environ` pointer before `setenv`: `0x7ffe49d25728` (the initial stack array); after `setenv("SPIKE_VAR", ...)`: `0x7cdbbc28ceb0` (heap): the pointer changes, so re-read it at every spawn. A child spawned with the live `environ` sees the value. **`System.getenv("SPIKE_VAR")` also returned it**, which the plan did not expect: the JDK's environment map is created at the first `System.getenv` call (here after the `setenv`), not at JVM start. The "with `System.getenv` it would not" demonstration therefore did not work in this run and was not repeated with an earlier `getenv` call. The design is unaffected: pass the live `environ`, never a Java-built array.
4. **Inherited state, no attributes vs `SETSIGMASK`, from `main` and from a second thread** (the same results on both threads). The JVM's own threads have `SigBlk 0x4`; the JVM process has `SigIgn 0` and `SigCgt 0x2000000101005ccf` (handlers, not `SIG_IGN`, for SIGPIPE and SIGXFSZ among them).

   | Child, spawned from | attribute | SigBlk | SigIgn |
   | --- | --- | --- | --- |
   | main thread | none | `0x0000000000000004` = SIGQUIT | `0x0` = - |
   | main thread | `SETSIGMASK` empty | `0x0000000000000000` = - | `0x0` = - |
   | second thread | none | `0x0000000000000004` = SIGQUIT | `0x0` = - |
   | second thread | `SETSIGMASK` empty | `0x0000000000000000` = - | `0x0` = - |

   (Read by `exec`ing `grep` directly on `/proc/self/status`; its `SigCgt 0x400` = SIGSEGV is grep's own handler. A child that is `/bin/sh` (dash) shows `SigBlk 0` even without the attribute: dash clears the mask itself, so the mask must be read from a directly exec'd program.) **So U1 holds: SIGQUIT is blocked in the child unless `SETSIGMASK` with an empty set is given. SIGPIPE and SIGXFSZ are not ignored.**
   - Descriptors in the child with no actions: `0, 1, 2` (the launcher's), **`3 -> /home/ubuntu/jdk-25/lib/modules`** (the JDK's jimage file, opened without `O_CLOEXEC`: `fdinfo` flags `0100000`), and `7 -> /dev/ptmx`, which came from the wrapper script (it is in the script's own fd table; with fds 3..20 closed before `exec java` it disappears, `3` stays).
   - `ulimit -n`: soft = hard = 1048576 (HotSpot raised the soft limit; the child inherits it).
   - `env | sort` of the child vs the JVM's own start environment: identical (21 vars each), both threads, both attribute settings.
5. **JDK reaper.** First half as stated: `sleep 1` through FFM, 3 s Java sleep, then `waitpid(pid, WNOHANG)` -> the pid, status 0 (JDK did not reap it). Second half (`ProcessBuilder sleep 2`, FFM `waitpid(-1, 0)`): a race between FFM and the JDK's per-pid reaper thread. FFM `waitpid(-1)` collected the child in 7 of 8 trials (after about 1990 ms for `sleep 2`, about 1000 ms for `sleep 1`; status 0), in 1 of 8 the JDK got it first and FFM saw -1/ECHILD. Either way `Process.waitFor()` finished and `exitValue()` was **0**. The child was `sleep`, whose real status is 0, so these trials cannot show what the JDK reports when it lost the status; plan 1.6 says 0, unmeasured here. (Trials mixed `sleep 2` and `sleep 1`; run1 used older code without timing.)
6. **pidfd: as stated.** `syscall(434, pid, 0)` -> fd 4; `poll` 50 ms while the child sleeps -> 0 (revents 0); after the child exited but before it was reaped -> 1 (`POLLIN`); `waitpid(getpid(), 0)` -> -1, errno 10; the child was then reaped normally (status 0).
7. **sysconf errno rule** (10 000 iterations per case, allocation between the errno write and the call, run on `main` and on a second thread, identical):

   | name | errno zeroed first | captured result |
   | --- | --- | --- |
   | `sysconf(32)` SC_SEM_NSEMS_MAX | yes | -1, errno **0** x 10 000 (non-zero: **0**) |
   | `sysconf(41)` SC_EQUIV_CLASS_MAX | yes | -1, errno 22 x 10 000; 10 000 of 10 000 confirmed on the second attempt |
   | `sysconf(99999)` | yes | -1, errno 22 x 10 000; 10 000 of 10 000 confirmed |
   | all three | no (control) | errno 22 x 10 000 each: the stale value from the preceding case, as N0 7.2 predicts |

   `sysconf(30)` = 4096 (sanity). The "no value" case never showed a non-zero errno when errno was zeroed first.
8. **SIGCHLD via `sun.misc.Signal`: accepted.** `Signal.handle(new Signal("CHLD"), ...)` raised no exception; afterwards the JVM's `SigCgt` is `0x2000000101015ccf` (SIGCHLD added). The handler **ran for the FFM child's exit** (signal 17, on a thread named `SIGCHLD handler`), and again for a second child (`/bin/true`) after re-registering. Both children were then reaped by `waitpid`.
9. **Timing, 200 iterations of `posix_spawn(/bin/true)` + `waitpid` (after 20 warm-up):**

   | variant | ms per spawn+wait |
   | --- | --- |
   | A baseline (JVM's own few fds) | 0.81 |
   | B +500 `O_CLOEXEC` fds open | 0.73 |
   | C = B + a `/proc/self/fd` listing (`Files.list` + count) before each spawn | 1.14 |
   | B2 +500 inheritable fds (child inherits them) | 0.69 |
   | D = B2 + `addclosefrom_np(3)` | 0.85 |
   | E = B2 + the listing before each spawn | 0.95 |

   Differences between A/B/B2/D are about 0.1 ms (single run; "noise" is a judgement); the `/proc/self/fd` listing costs about 0.25 to 0.4 ms. `addclosefrom_np` is cheap.

## Stop conditions

- **SIGPIPE or SIGXFSZ ignored in the JVM: NOT triggered.** `SigIgn` is 0 for the JVM and for every child; SIGPIPE (13) and SIGXFSZ (25) are *caught* by HotSpot handlers (they are in the JVM's `SigCgt`) and are default in a child. Gap 1.6(a) stands as written.
- **JVM passes inheritable descriptors above 2 of its own: TRIGGERED.** fd 3 (`$JAVA_HOME/lib/modules`, the jimage file) is not close-on-exec and appears in every child spawned without `addclosefrom_np`/`addclose`, on both threads. (fd 7 `/dev/ptmx` in the first runs was the wrapper's, not the JVM's; fd 4/5 in the JVM's own listing were the spike's `/proc/self/fd` directory streams; **fd 5 is itself not close-on-exec** (`flags=0100000`, run1.log:33, run2.log:42, run3.log:9), consistent with the JDK's directory stream opening with CLOEXEC and then `dup`ing without it before `fdopendir` (inference, not shown). Any JDK directory listing therefore holds an inheritable descriptor while it is open: a spawn on another thread at that moment would pass it to the child unless the spawn closes it. Reported to the planner with fd 3.) `close_fds=False` would leak fd 3 into children, and `os.posix_spawn` with no file actions too. 1.8 needs a rule (for example always `addclosefrom_np(3)` unless the caller passed `pass_fds`/`file_actions` that need those descriptors, or close fd 3 explicitly): **report to the planner.** Whether the JVM's own descriptor numbers are stable (3 here) was not established across JVM options.
- **JDK reaped the FFM child: NOT triggered.** Item 5 first half: status intact after 3 s. (The second half is a race with a `ProcessBuilder` child only; FFM children are invisible to the JDK.)
- **Item 7 "no value" errno non-zero more than a handful of times: NOT triggered.** 0 of 10 000 per thread (20 000 total) with errno zeroed first. The 1.3 rule works as written; without zeroing the value is stale (22), as expected.

## Other observations for the plan

- SIGQUIT is blocked in the child unless `SETSIGMASK` with an empty set is passed (item 4): `fork_exec` and `os.posix_spawn` should always pass an empty or the requested sigmask (U1 confirmed; the plan's existing 1.4 should state it if it does not).
- `System.getenv` is not a reliable witness for `setenv` (item 3); irrelevant to the design, which reads `environ`.

## Not measured

- glibc 2.35 host (U2); other JVM options (`-Xshare`, CDS archive, different launchers) for the number of the `modules` descriptor; `RLIMIT_NOFILE` effects beyond reading `ulimit -n`.
- Item 3: the "System.getenv would not show it" contrast (needs a getenv call before the setenv).
