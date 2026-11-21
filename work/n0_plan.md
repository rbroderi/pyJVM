# N0 plan: POSIX descriptors become kernel descriptors (FFM), plus select/poll/epoll

Written 2026-11-16 by a planning agent. Parent: `native_modules_plan.md` (phase N0, decisions D1/D4/D5, "Decisions (2026-11-16)" items 1 and 3). Target: Linux x86-64 glibc only. Windows behaviour must not change.

Executors: one implementer agent per ticket, of the tier named on the ticket's "Implementer tier" line (Sonnet by default; Opus where the line says so; Haiku only for fully mechanical work; never Fable). After the implementer reports, a **Haiku agent reruns the ticket's acceptance tests and the mechanical checks** (W-min/L-min, methodsize, the grep/diff checks of section 4 items 1, 2 and 10) and hands the logs to the reviewer. Then an Opus agent reviews the ticket's diff (using that ticket's review checklist plus section 4) before the next ticket starts. Every design choice is made here. If an implementer finds a fact that contradicts this plan, they stop and report; they do not pick an alternative.

## 0. How this plan was checked (and what was not)

Measured for this plan, with **CPython only** (no pyJVM, no JVM) in WSL Ubuntu 22.04, glibc 2.35, x86_64, on `~/pyjvm/.venv315` (3.15.2) and `.venv313` (3.13.18):
- every constant, struct layout and libc symbol in section 3 (struct stat by comparing a raw `fstat` buffer with `os.fstat`; `pollfd`, `epoll_event`, `fd_set`/`timeval` by calling libc through ctypes);
- every CPython behaviour quoted as "measured" in section 3.6 and in the tickets.

**Not verified** (flagged again where used):
- U1. No 3.14 interpreter exists in WSL yet, so every lane-dependent item is known for 3.13 and 3.15 only. Ticket 0 measures 3.14; the rule for each item is given in section 3.7.
- U2. No Java/FFM code was run. All FFM statements (options combine, `Arena.ofAuto` segments usable across threads, `defaultLookup` finds these symbols) come from the Java 25 API documentation and from the existing `PosixNative.java`. Ticket 1 (spike) checks them.
- U3. HotSpot signal facts: SIGPIPE is ignored by the JVM so a write to a broken pipe returns EPIPE; JVM handlers are installed with SA_RESTART; a signal may be taken by any thread. From HotSpot knowledge, not measured. Ticket 1 checks the first one.
- U4. The Windows gate numbers in HANDOFF are from before the in-flight hashlib/OSError work; ticket 0 re-baselines.
- U5. `pyjvm315/conformance.py:31` copies `os.environ` into the child environment, so `PYJVM_POSIX_FDS` should reach the JVM; read, not run.
- U6. The line count (about 1,300 new/changed Java lines against the parent plan's 900) is an estimate.

## 1. Design

### 1.1 Architecture
- **No Java-side descriptor table on POSIX.** The Python-visible number is the kernel's. Append mode, access mode, directory-ness, offsets and close-on-exec all live in the kernel (`O_APPEND`, `EBADF`, `fstat`, `FD_CLOEXEC`). This is what lets N1 (`posix_spawn`) and N3 (sockets) create descriptors without registering them anywhere for I/O to work.
- `IoFd` keeps its static API. Each method gets one guard line first: `if (KERNEL) return PosixFds.x(...);`. The existing body below the guard is not edited. `IoFd.KERNEL` is a `static final boolean`, always false on Windows.
- New POSIX-only classes (never initialised on Windows):
  - `PosixNative` (exists; extended): FFM plumbing, errno, error construction, host check.
  - `PosixFds`: descriptor syscalls and the D5 signal rule.
  - `PosixOsFds`: the `posix` module functions that exist only with kernel descriptors (registered over the shared ones).
  - `PosixSelect`: FFM for `select`, `poll`, `epoll_*`.
  - `PosixSelectModule`: the POSIX `select` module (select, poll, epoll).
  - `PosixErrnoText`: generated strerror table.
- Shared files get the smallest possible edit: `IoFd` (guards), `PosixFiles.install` (one line), `SelectModule.create` (one line plus visibility of four helpers), `SignalModule` (POSIX wakeup fd, `isMain` visibility), `IoFile`/`IoBuffered` (non-blocking returns, finalizer guard), `GcFinalizable` (a thread-local depth counter), `PosixModule.strerror` (POSIX branch only).
- Path-based functions (`stat`, `listdir`, `rename`, `mkdir`, ...) stay on java.nio in N0 (parent plan D1).
- The emulated working directory stays (`PosixModule.cwd`; the JVM cannot safely `chdir(2)`: its own relative class-path entries would break). `open` resolves a relative path against it by string join (1.5). Consequence for N1, recorded here: `posix_spawn` must add a chdir file action to the emulated cwd whenever `PosixModule.cwd` is set.

### 1.2 Switch, migration, rollback
- Environment variable `PYJVM_POSIX_FDS`, read once in `IoFd`'s static initialiser, ignored on Windows.
  - Tickets 3, 4, 4b: `KERNEL = !ImportSupport.WINDOWS && "kernel".equals(System.getenv("PYJVM_POSIX_FDS"))` (opt-in; default is today's java.nio path).
  - Ticket 5 flips it: `KERNEL = !ImportSupport.WINDOWS && !"nio".equals(System.getenv("PYJVM_POSIX_FDS"))` (default kernel; `nio` is the rollback).
  - Tickets 6-13 add features that exist only when `KERNEL` (pipe, dup2, epoll, real inheritable flags). In `nio` mode they are absent exactly as today, and the new conformance cases fail there by design.
  - Ticket 14 (deferred, owner's go-ahead, after N1 has run on top of N0 for one clean gate cycle) removes the switch and the POSIX-only java.nio leftovers.
- Rollback at any point before ticket 14: set `PYJVM_POSIX_FDS=nio`. Rollback of a single ticket: revert its commit; tickets are ordered so that each leaves both modes working.
- Work happens on branch `n0-fds` in a worktree `C:\Users\richa\workspace_local\pyjvm-work\wt-n0`, one local commit per ticket, never pushed (owner's answer Q2). Base commit **B0 = `4405c12`** (2026-11-17, branch `ccr-53990e90-8do8kj`; worktree created): the commit that contains today's in-flight Windows work (hashlib, OSError leftovers), which is committed first; N0 branches from it. Do not start from a dirty tree.

### 1.3 FFM conventions (all new POSIX code)
- One helper builds every handle: `PosixNative.fn(String name, FunctionDescriptor d, Linker.Option... extra)` = `LINKER.downcallHandle(LINKER.defaultLookup().findOrThrow(name), d, CAPTURE + extra)`. Every handle captures errno (`Linker.Option.captureCallState("errno")`), so every `invokeExact` takes the call-state segment as its first argument. Exceptions: `malloc`, `free`, `getuid`, `geteuid`, `getgid`, `getegid`, `getppid`, `umask` are built without capture (they cannot fail).
- Variadic C functions are declared with `Linker.Option.firstVariadicArg(2)`: `open`, `fcntl`, `ioctl`.
- Per-thread native state, no Arena per call: `PosixNative.Tl { MemorySegment cs; MemorySegment buf; long cap; }` in a `ThreadLocal`. `cs` = `Arena.ofAuto().allocate(captureStateLayout())`; `buf` = `Arena.ofAuto().allocate(65536)`, grown (new `ofAuto` segment) on demand up to 1 MiB.
- I/O larger than 1 MiB uses libc `malloc(n)`/`free` (a `try/finally`), `MemorySegment.reinterpret(n)`. Reason: `Arena.allocate` zero-fills, and a read of 1 GiB must be one `read(2)`, as in CPython. `malloc` returning NULL raises `MemoryError`.
- No `Linker.Option.critical`: these calls can block.
- `invokeExact` throws `Throwable`: wrap as the existing code does (`catch (Throwable t) { throw rethrow(t); }`). `PosixNative.rethrow` becomes package-private. Never call anything on `WindowsNative` from a `Posix*` class.
- C types: `int`, `mode_t`, `uid_t`, `pid_t` -> `JAVA_INT`; `size_t`, `ssize_t`, `off_t`, `nfds_t`, `unsigned long` -> `JAVA_LONG`; pointers -> `ADDRESS`. Unsigned 32-bit results go through `Integer.toUnsignedLong`.
- Struct fields are read with explicit offsets on the segment (`seg.get(JAVA_LONG, 48)`), using `JAVA_LONG_UNALIGNED`/`JAVA_INT_UNALIGNED` where section 3 says "packed". No `StructLayout` objects for these.

### 1.4 Errors
- `PosixNative.errno(MemorySegment cs)` reads the captured value. `PosixNative.osError(int errno, String filename)` = `PosixModule.osError(ErrnoModule.osErrorType(errno), errno, PosixNative.strerror(errno), filename)`. The private five-case `error()` switch in `PosixNative` is deleted in favour of it.
- `PosixErrnoText.strerror(int)` reads the generated table `PosixErrnoText.TEXT` (errno 0..133, produced by `tools/gen_posix_tables.py` under Linux CPython; index 0 is `"Success"`, CPython's `os.strerror(0)` on Linux); anything else is `"Unknown error N"` (measured for 134 and -1). The committed table was generated on glibc 2.39 (Ubuntu 24.04); glibc 2.35 gives the same 133 entries (count and end texts checked in 7.1, and ticket 2's acceptance compares all of 0..135 with CPython). Decision: a table, not libc `strerror`, because the JVM launcher calls `setlocale(LC_ALL, "")` and libc texts could then be translated where CPython (which sets only `LC_CTYPE`) prints English. `PosixNative.strerror` is a one-line delegate.
- `PosixModule.strerror` on POSIX calls `PosixErrnoText.strerror` directly (the Windows branch on its first line is untouched). `PosixErrnoText` holds data only, so `os.strerror` does not initialise any FFM handle.
- Descriptor arguments: `PosixFds.cfd(long fd)` throws `OverflowError("Python int too large to convert to C int")` when the value does not fit an int (measured: `os.close(2**40)`); negative values go to the kernel and come back as EBADF (measured).

### 1.5 Paths
`PosixFds.pathBytes(String path)`:
- contains `'\0'` -> `ValueError("open: embedded null character in path")` (measured, both lanes; `IoFile` has its own earlier check with its own text, unchanged);
- empty, or absolute, or the emulated cwd is unset -> the path itself; otherwise `cwd + "/" + path` with **no** normalisation (the kernel resolves `..` through symlinks; `Path.normalize()` would not);
- encoded with `Codecs.encode(s, "utf-8", "surrogateescape")`, NUL-terminated into a confined Arena for the duration of the call.
- The error's `filename` is always the string the caller passed, never the joined one.
- Known limitation kept (not N0's to fix): `PosixModule.pathString` decodes `bytes` paths as strict UTF-8, so non-UTF-8 file names are already lossy before they reach `open`.

### 1.6 D5: signals and blocking calls (the one rule, reused by N1 and N3)
Facts: Python handlers run only on the main thread, at `SignalModule.dispatchPending()`. A signal's OS-level delivery may land on any JVM thread, so the main thread parked in `read(2)` is generally **not** interrupted. Therefore:
1. **Pre-wait on the main thread.** Before a `read` or `write` syscall, if `SignalModule.active && SignalModule.isMain()`, call `PosixFds.awaitReady(fd, POLLIN or POLLOUT)`:
   ```
   loop:
     n = poll({fd, events}, 1, 50)          // 50 ms slice
     n > 0  -> return                       // ready, or POLLERR/POLLHUP/POLLNVAL: let the syscall report it
     n < 0  -> errno == EINTR ? (dispatchPending(); continue) : throw osError
     n == 0 -> first time only: if (fcntl(fd, F_GETFL) & O_NONBLOCK) != 0 return   // non-blocking: let the syscall return EAGAIN
               dispatchPending(); continue
   ```
   Regular files and directories are always ready, so the cost for them is one extra `poll` per read/write.
2. **EINTR (PEP 475).** `open`, `read`, `write`, `pread`, `pwrite`, `fsync`, `fdatasync`, `ftruncate`, `fstat`, `select`, `poll`, `epoll_wait` retry on EINTR after `SignalModule.dispatchPending()` (a no-op off the main thread); waits recompute the remaining timeout from a `System.nanoTime()` deadline. **`close`, `dup2`, `dup3` are never retried** (Linux closes the descriptor even when `close` returns EINTR; CPython does not retry them either).
3. **Buffer re-entrancy.** `dispatchPending()` runs arbitrary Python, which may call `os.read`/`os.write` on this thread and reuse the thread-local buffer. So: never call `dispatchPending()` between a syscall and the copy-out of its result; after any `dispatchPending()` on the write path, copy the input into the buffer again before retrying. The pre-wait runs before the copy-in.
4. A handler that raises propagates out of the I/O call before any byte was transferred (the pre-wait happens before the syscall).
5. Waits in `select`/`poll`/`epoll` use the same 50 ms slices on the main thread when `SignalModule.active`; other threads make one call with the full timeout.
6. Accepted limits (document in JVM_BACKEND): a signal handler runs up to 50 ms late while the main thread is blocked; a blocking `write` larger than the pipe capacity cannot be interrupted once it is inside `write(2)`; non-main threads never slice. An eventfd-based wakeup (zero latency, no idle wakeups) is a possible later optimisation, deliberately not in N0.

### 1.7 Descriptor lifetime and the finalizer guard
- `_io` objects close their descriptor from `__del__` (`IoBase.del`, delivered through `GcFinalizable`), i.e. after a collection, later than CPython. That is unchanged. With real descriptors two consequences matter: a pipe write end held by garbage delays EOF (document; N1 checks `subprocess`), and a late `__del__` can close a **number that has since been reused** by an unrelated open (in CPython the stale close happens immediately and fails harmlessly with EBADF).
- Guard: `PosixFds` keeps `ConcurrentHashMap<Integer, Long> GEN` and an `AtomicLong` counter. `adopt(fd)` (called after every successful descriptor-creating call) stores a fresh generation; `forget(fd)` removes it and is called **before** `close(2)`; `gen(fd)` returns 0 for an unknown number. `IoFile.St` records the generation at `init`. A close that runs **inside a finalizer** (`GcFinalizable.inDel()`) and finds a different generation skips the `close(2)`. An explicit close always closes. The map is not a descriptor table: nothing reads it for I/O.
- Every later phase that creates descriptors (N1 pipes for spawn, N3 `socket`/`accept`/`socketpair`) must call `PosixFds.adopt`.
- Close-on-exec: every descriptor the runtime creates is created atomically non-inheritable (`O_CLOEXEC`, `pipe2(O_CLOEXEC)`, `F_DUPFD_CLOEXEC`, `dup3(O_CLOEXEC)`, `EPOLL_CLOEXEC`). The only inheritable ones are those Python asked for: `os.dup2(..., inheritable=True)` (the default), `os.pipe2(flags)` without `O_CLOEXEC`, `os.set_inheritable(fd, True)`.
- Descriptors 0-2 are no longer special: `os.close(1)` really closes it (CPython does). JVM-owned descriptors can be closed by number from Python (parent plan risk 7): no guard, by decision.

## 2. Environment and workflow

### 2.1 Trees
- Windows: worktree `pyjvm-work\wt-n0` on branch `n0-fds` (from B0). Use the main repo's venv interpreters with `PYTHONPATH` = the worktree. The helper scripts in `pyjvm-work` hard-code the main repo path (`run_cases.ps1`, `run_conf.ps1`); ticket 0 writes `-Tree` variants under `pyjvm-work\n0\`. Separate caches: `pyjvm-work\n0\bc-<lane>`, `c-<lane>`.
- WSL: a second clone `~/pyjvm-n0` (`git clone /mnt/c/Users/richa/workspace_local/pyjvm ~/pyjvm-n0`, `git checkout n0-fds`; update with `git pull --ff-only`). The clone checks out LF; the Windows worktree has CRLF (`core.autocrlf=true`), so never copy files across by hand. Own venvs and caches (ticket 0). `~/pyjvm` stays untouched for the regular Linux gates.

### 2.2 Rules for every ticket
- **Run lock.** pyJVM runs in WSL are allowed for N0 (owner's answer Q1), under the shared one-run-at-a-time lock `C:\Users\richa\workspace_local\pyjvm-work\run.lock`. Before **any** build, test, gate or pyJVM run, on Windows or in WSL, create the lock atomically; remove it in a `finally`; if it already exists, wait and retry (never delete a lock you did not create). Write who/what/when into it. PowerShell: `New-Item -ItemType File -Path <lock> -ErrorAction Stop` (fails if it exists) inside `try { ... } finally { Remove-Item <lock> }`. WSL takes the same file: `set -o noclobber; echo "<who> <what> $(date)" > /mnt/c/Users/richa/workspace_local/pyjvm-work/run.lock || exit 75; trap 'rm -f /mnt/c/Users/richa/workspace_local/pyjvm-work/run.lock' EXIT`. The scripts of ticket 0 (`linux_gates.sh`, `conf_cases.sh`, `run_cases_n0.ps1`, `run_conf_n0.ps1`) take the lock themselves, so callers do not nest it. The CPython-only measurement scripts do not need it.
- One gate at a time, never alongside another agent's run (memory; HANDOFF "Memory"). The first cold conformance case per lane and cache takes 8-13 minutes. Delete `hs_err_pid*.log`/`replay_pid*.log` after a crash.
- Use uv-managed Pythons; never `python3` on Windows. WSL commands go into a script file run with `wsl -d Ubuntu -u ubuntu -- bash <script>` (quoting through PowerShell breaks); write scripts with the Write tool, never heredocs.
- Each ticket's "Acceptance" lists what must pass. In addition, every ticket runs:
  - **W-min (Windows, in the worktree):** runtime compiles (`javac --release 25`, as the test suite does), `python -m pyjvm315.methodsize --runtime` reports 0 methods over 8000 bytes, `pytest tests/test_import_runtime.py -q`, and conformance cases `os_pipe, os_platform, io_files, select_files, signal_basic, subprocess_windows, native_stdlib_modules` on the 3.15 lane.
  - **L-min (WSL, `~/pyjvm-n0`):** conformance cases `io_files, io_posix, io_memory, os_fchmod, os_putenv, os_platform, select_files, signal_basic, encoding_defaults` on the three lanes, in the mode(s) the ticket names.
- **W-full / L-full** (tickets 5 and 13 only): pytest, methodsize, full conformance on three lanes; L-full also the strict lane; compared against ticket 0's baseline lists (same failing set or smaller).
- The reviewer gets: the ticket text, `git diff <previous ticket>..HEAD`, and the logs of the acceptance runs. The implementer's report states which runs were made and their pass counts; "not run" must be said explicitly.

## 3. Reference data (Linux x86-64, glibc 2.35; measured unless marked)

### 3.1 libc functions (all present in `libc.so.6` on the gate host)
| C prototype | FunctionDescriptor | Notes |
| --- | --- | --- |
| `int open(const char*, int, ...)` | `of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)` | `firstVariadicArg(2)`; third arg is the mode |
| `int close(int)` | `of(JAVA_INT, JAVA_INT)` | never retried |
| `ssize_t read(int, void*, size_t)` / `write` | `of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG)` | |
| `ssize_t pread(int, void*, size_t, off_t)` / `pwrite` | `of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG)` | |
| `off_t lseek(int, off_t, int)` | `of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT)` | |
| `int fstat(int, struct stat*)` | `of(JAVA_INT, JAVA_INT, ADDRESS)` | a real symbol since glibc 2.33 |
| `int fstatat(int dirfd, const char*, struct stat*, int flags)` | `of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)` | `AT_FDCWD` -100, `AT_SYMLINK_NOFOLLOW` 256 (measured through ctypes: follows and does not follow a symlink, dangling link -> errno 2) |
| `int ftruncate(int, off_t)` | `of(JAVA_INT, JAVA_INT, JAVA_LONG)` | |
| `int fsync(int)`, `fdatasync(int)`, `isatty(int)` | `of(JAVA_INT, JAVA_INT)` | |
| `int fcntl(int, int, ...)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)` | `firstVariadicArg(2)`; pass 0 for getters |
| `int dup2(int, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT)` | |
| `int dup3(int, int, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)` | |
| `int pipe2(int[2], int)` | `of(JAVA_INT, ADDRESS, JAVA_INT)` | two `JAVA_INT` at offsets 0 and 4 |
| `int fchmod(int, mode_t)` | exists in `PosixNative` | |
| `int ioctl(int, unsigned long, ...)` | `of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS)` | `firstVariadicArg(2)` |
| `uid_t getuid(void)`, `geteuid`, `getgid`, `getegid`; `pid_t getppid(void)` | `of(JAVA_INT)` | no capture |
| `mode_t umask(mode_t)` | `of(JAVA_INT, JAVA_INT)` | no capture |
| `void* malloc(size_t)`, `void free(void*)` | `of(ADDRESS, JAVA_LONG)`, `ofVoid(ADDRESS)` | no capture |
| `const char* gnu_get_libc_version(void)` | `of(ADDRESS)` | host check only (returned "2.35") |
| `int select(int, fd_set*, fd_set*, fd_set*, struct timeval*)` | `of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)` | |
| `int poll(struct pollfd*, nfds_t, int)` | `of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)` | |
| `int epoll_create1(int)` | `of(JAVA_INT, JAVA_INT)` | |
| `int epoll_ctl(int, int, int, struct epoll_event*)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)` | |
| `int epoll_wait(int, struct epoll_event*, int, int)` | `of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT)` | |

### 3.2 Struct layouts
- `struct stat`, size 144: `st_dev` u64 @0, `st_ino` u64 @8, `st_nlink` u64 @16, `st_mode` u32 @24, `st_uid` u32 @28, `st_gid` u32 @32, (pad @36), `st_rdev` u64 @40, `st_size` i64 @48, `st_blksize` i64 @56, `st_blocks` i64 @64, `st_atim` {sec i64 @72, nsec i64 @80}, `st_mtim` {@88, @96}, `st_ctim` {@104, @112}, reserved to 144. Allocate 144 bytes.
- `struct pollfd`, size 8: `fd` i32 @0, `events` i16 @4, `revents` i16 @6.
- `struct epoll_event`, **packed**, size 12: `events` u32 @0, `data` u64 @4 (use `JAVA_LONG_UNALIGNED`). Array stride 12.
- `fd_set`, size 128 (FD_SETSIZE 1024): descriptor `fd` is bit `fd % 8` of byte `fd / 8`.
- `struct timeval`, size 16: `tv_sec` i64 @0, `tv_usec` i64 @8. Linux `select` rewrites it; rebuild it for every call.
- `struct winsize`, size 8: `ws_row` u16 @0, `ws_col` u16 @2, `ws_xpixel` u16 @4, `ws_ypixel` u16 @6. Standard layout, **not measured**: the gate has no terminal, so only the ENOTTY error path is tested; check the success path once by hand in a WSL terminal against CPython (ticket 4).

### 3.3 Constants (decimal)
- open: `O_RDONLY` 0, `O_WRONLY` 1, `O_RDWR` 2, `O_ACCMODE` 3, `O_CREAT` 64, `O_EXCL` 128, `O_NOCTTY` 256, `O_TRUNC` 512, `O_APPEND` 1024, `O_NONBLOCK`=`O_NDELAY` 2048, `O_DSYNC` 4096, `O_ASYNC` 8192, `O_DIRECT` 16384, `O_LARGEFILE` 0, `O_DIRECTORY` 65536, `O_NOFOLLOW` 131072, `O_NOATIME` 262144, `O_CLOEXEC` 524288, `O_SYNC`=`O_RSYNC`=`O_FSYNC` 1052672, `O_PATH` 2097152, `O_TMPFILE` 4259840. `SEEK_SET/CUR/END` 0/1/2, `SEEK_DATA` 3, `SEEK_HOLE` 4. (The values already in `PosixFiles.java:36-39` agree.)
- fcntl: `F_DUPFD` 0, `F_GETFD` 1, `F_SETFD` 2, `F_GETFL` 3, `F_SETFL` 4, `F_DUPFD_CLOEXEC` 1030, `FD_CLOEXEC` 1.
- ioctl: `TIOCGWINSZ` 21523 (0x5413).
- errno: `EPERM` 1, `ENOENT` 2, `EINTR` 4, `EBADF` 9, `EAGAIN` 11, `EEXIST` 17, `ENOTDIR` 20, `EISDIR` 21, `EINVAL` 22, `ENOTTY` 25, `ESPIPE` 29, `EPIPE` 32.
- poll: `POLLIN` 1, `POLLPRI` 2, `POLLOUT` 4, `POLLERR` 8, `POLLHUP` 16, `POLLNVAL` 32, `POLLRDNORM` 64, `POLLRDBAND` 128, `POLLWRNORM` 256, `POLLWRBAND` 512, `POLLMSG` 1024, `POLLRDHUP` 8192; `PIPE_BUF` 4096.
- epoll: `EPOLLIN` 1, `EPOLLPRI` 2, `EPOLLOUT` 4, `EPOLLERR` 8, `EPOLLHUP` 16, `EPOLLRDNORM` 64, `EPOLLRDBAND` 128, `EPOLLWRNORM` 256, `EPOLLWRBAND` 512, `EPOLLMSG` 1024, `EPOLLRDHUP` 8192, `EPOLLWAKEUP` 536870912 (lane-dependent, 3.7), `EPOLLONESHOT` 1073741824, `EPOLLET` 2147483648, `EPOLL_CLOEXEC` 524288. `EPOLL_CTL_ADD` 1, `EPOLL_CTL_DEL` 2, `EPOLL_CTL_MOD` 3 (ADD measured; DEL/MOD from the kernel headers). `EPOLLEXCLUSIVE` is **absent** from the reference interpreters' `select` module: do not add it.

### 3.4 `select` module surface on Linux (measured, `dir(select)` without dunders)
3.13: the 12 `POLL*` names, `PIPE_BUF`, 13 `EPOLL*` names plus `EPOLL_CLOEXEC`, `epoll`, `error`, `poll`, `select`. 3.15: the same plus `EPOLLWAKEUP`. `select.epoll` attributes: `close`, `closed`, `fileno`, `fromfd`, `modify`, `poll`, `register`, `unregister`, `__enter__`, `__exit__`. `select.poll()` object: `modify`, `poll`, `register`, `unregister`.

### 3.5 Signatures (`__text_signature__`, identical on 3.13 and 3.15)
`dup2(fd, fd2, inheritable=True)` (keywords allowed); positional-only: `pipe2(flags)`, `set_inheritable(fd, inheritable)`, `get_inheritable(fd)`, `set_blocking(fd, blocking)`, `get_blocking(fd)`, `pread(fd, length, offset)`, `pwrite(fd, buffer, offset)`, `closerange(fd_low, fd_high)`, `readinto(fd, buffer)` (3.15). `epoll.register(fd, eventmask=EPOLLIN|EPOLLPRI|EPOLLOUT)`, `epoll.modify(fd, eventmask)`, `epoll.unregister(fd)`, `epoll.poll(timeout=None, maxevents=-1)` (keywords allowed), `epoll.fromfd(fd)` (class method, positional-only), `select.epoll(sizehint=-1, flags=0)`.

### 3.6 CPython behaviour to match (measured on 3.13 and 3.15; identical unless 3.7 says otherwise)
Descriptors:
- `os.open` result is non-inheritable (`F_GETFD` == 1). `os.open(dir, O_RDONLY)` succeeds; `os.open(dir, O_WRONLY)` -> `IsADirectoryError(21)` with filename; `O_DIRECTORY` on a file -> `NotADirectoryError(20)` with filename. `os.open("")` -> `FileNotFoundError(2)` with filename `''`. A created file gets `mode & ~umask` (0666 under umask 022 -> 0644).
- `os.read(dirfd, n)` -> `IsADirectoryError(21)`, no filename. `os.lseek(dirfd, 0, 1)` -> 0. `os.fsync(dirfd)` -> None. `os.lseek(pipe, 0, 0)` -> `OSError(29, 'Illegal seek')`. `os.lseek(fd, 0, 99)` -> `OSError(22)`.
- `io.FileIO(dir)` and `open(dir)` -> `IsADirectoryError: [Errno 21] Is a directory: '<path>'`; `io.FileIO(dirfd)` -> the same with filename = the int.
- `os.write` on a read-only fd and `os.read` on a write-only fd -> `OSError(9)`. `os.ftruncate(fd, -1)` and `os.ftruncate(read-only fd, 0)` -> `OSError(22)`. `os.fsync(read-only fd)` -> None. `os.read(fd, -1)` -> `OSError(22)`.
- Bad descriptor (`9999`): `close`, `fstat`, `get_inheritable`, `set_blocking`, `dup`, `dup2` -> `OSError(9)`; `os.isatty(9999)` -> False. `os.close(-1)` -> `OSError(9)`. `os.close(2**40)`, `os.closerange(2**40, 2**41)` -> `OverflowError: Python int too large to convert to C int`.
- `os.dup(fd)` -> non-inheritable. `os.dup2(fd, fd2)` -> returns fd2, **inheritable** (default True); `inheritable=False` -> non-inheritable. `os.dup2(fd, fd)` -> fd (no error). `os.dup2(fd, fd, inheritable=False)` -> `OSError(22)`. `os.dup2(fd, -1)` -> `OSError(9)`.
- `os.pipe()` -> both ends non-inheritable, blocking; `F_GETFL` read end 0, write end 1. `os.pipe2(O_NONBLOCK|O_CLOEXEC)` -> non-inheritable, non-blocking. `os.pipe2(0)` -> inheritable, blocking. `os.pipe2(12345678)` -> `OSError(22)`. Pipe `fstat`: `S_ISFIFO`, `st_size` 0. Empty non-blocking pipe: `os.read` -> `BlockingIOError(11, 'Resource temporarily unavailable')`. Write with the read end closed -> `BrokenPipeError(32)` (SIGPIPE is ignored by CPython; see U3 for the JVM).
- `O_APPEND`: after `lseek(fd, 0, 0)` a `write` still lands at the end and the offset is then the file size. `os.pwrite(fd, b"hello", 3)`/`os.pread(fd, 4, 2)` do not move the offset. `os.pread(fd, -1, 0)` -> `OSError(22)`.
- `os.set_inheritable(fd, True)` -> `F_GETFD` 0. `os.closerange(a, b)` ignores errors, including `a > b`. `os.get_terminal_size(pipe)` -> `OSError(25, 'Inappropriate ioctl for device')`. `os.umask(m)` returns the previous mask; `os.umask(0o7777777)` stores `0o777`.
- Type errors: a float/str descriptor -> `TypeError: 'float' object cannot be interpreted as an integer`; `os.write(1, "x")` -> `TypeError: a bytes-like object is required, not 'str'`.
Path stat (ticket 4b):
- `os.stat(p) == os.fstat(fd)` for the same file, including `st_blocks`, `st_blksize`, `st_rdev` and the `_ns` times. `os.stat(fd_int) == os.fstat(fd_int)`; `os.stat(9999)` -> `OSError: [Errno 9] Bad file descriptor: 9999` (filename is the int). `os.lstat(int)` -> `TypeError: lstat: path should be string, bytes or os.PathLike, not int`. `os.stat(1.5)` -> `TypeError: stat: path should be string, bytes, os.PathLike or integer, not float`.
- Missing path and dangling symlink -> `FileNotFoundError(2)` with filename; `os.lstat(dangling)` works (`S_ISLNK`, `st_size` = length of the target). Path through a regular file -> `NotADirectoryError(20)` with filename. `os.stat("")` -> `FileNotFoundError(2)`, filename `''`. A 300-character component -> `OSError(36, 'File name too long')` with filename. `os.stat("a\0b")` -> `ValueError: stat: embedded null character in path`; `os.lstat` -> the same with `lstat:`.
- `os.stat("/dev/null")`: `S_ISCHR`, `st_rdev == os.makedev(1, 3)`. `DirEntry.inode() == os.lstat(e.path).st_ino`; `DirEntry.stat() == os.stat(e.path)`; `DirEntry.stat(follow_symlinks=False) == os.lstat(e.path)`; `DirEntry.stat()` of a dangling link -> `FileNotFoundError(2)`.
- Signatures: `stat(path, *, dir_fd=None, follow_symlinks=True)`, `lstat(path, *, dir_fd=None)`.
Non-blocking `_io` (empty non-blocking pipe unless stated):
- `FileIO.read(10)`, `readall()`, `readinto(buf)` -> `None`. `FileIO.write` on a full pipe -> `None`; a partial write returns the count (65536 of 70000).
- `BufferedReader.read(10)` -> `None`; `read()` -> `None`; `readinto(buf)` -> `None`; `read1(10)`, `readline()`, `peek(1)` -> `b''`. With 3 bytes available: `read(10)`, `read()`, `readline()` -> `b'abc'`.
- `TextIOWrapper.read(5)`, `readline()` -> `''`.
- `BufferedWriter(raw, 16)` over a full pipe: `write(b"12345678")` -> 8; `write(25 bytes)` -> `BlockingIOError(11, 'write could not complete without blocking')` with `characters_written == 8`; `flush()` -> the same error with `characters_written == 0`; after the pipe is drained `flush()` -> None.
select / poll / epoll:
- `select.select`: a closed descriptor below 1024 -> `OSError(9)`; any descriptor >= 1024 -> `ValueError: filedescriptor out of range in select()`; negative timeout -> `ValueError: timeout must be non-negative`. Pipe with data: `([r], [w], [])`. Read end whose writer closed: readable, not exceptional. Write end whose reader closed: writable, not exceptional.
- `poll`: results in registration order; an invalid descriptor -> `(fd, POLLNVAL)`; a float timeout is accepted (ms, rounded up); `modify` of an unregistered descriptor -> `FileNotFoundError(2)`; `unregister` of one -> `KeyError`; a read end whose writer closed -> `(fd, POLLHUP)`; `register(fd, 70000)` -> `OverflowError: Python int too large for C unsigned short` (the existing `SelectModule.mask` text "too large to convert to" differs; the new POSIX code uses the measured text).
- `epoll`: `fileno() > 2`, non-inheritable; `register` twice -> `FileExistsError(17)`; `modify`/`unregister` unknown -> `FileNotFoundError(2)`; `register(regular file)` -> `PermissionError(1)`; `register(9999)` -> `OSError(9)`; `register(-1)` -> `ValueError: file descriptor cannot be a negative integer (-1)`; `register("x")` -> `TypeError: argument must be an int, or have a fileno() method.`; default mask = IN|PRI|OUT; `register(fd, -1)` -> `OSError(22)`. `poll(0, 0)` and `poll(0, -2)` -> `ValueError: maxevents must be greater than 0, got 0` (resp. `-2`); `maxevents=-1` works; `timeout=None` and negative timeouts block; `EPOLLONESHOT` reports once. After `close()`: `closed` True, a second `close()` is fine, `poll`/`fileno`/`register` -> `ValueError: I/O operation on closed epoll object`. `select.epoll(0)` -> `ValueError: negative sizehint`; `select.epoll(flags=12)` -> `OSError('invalid flags')` (no errno); `sizehint=-1` and `flags=EPOLL_CLOEXEC` are accepted. `with select.epoll() as e:` closes it. `repr` starts `<select.epoll object at`.
Signals:
- `signal.set_wakeup_fd(fd)`: a blocking descriptor -> `ValueError: the fd N must be in non-blocking mode`; `9999` -> `OSError(9)`; returns the previous value (`-1` first); a regular file is accepted; the byte written is the signal number. A full pipe with `warn_on_full_buffer=True` prints to stderr a header line (3.7) then `BlockingIOError: [Errno 11] Resource temporarily unavailable`; with `False` nothing.
- Main thread blocked in `os.read(pipe)`, another thread calls `signal.pthread_kill(main_ident, SIGUSR1)`: the handler runs, and (it wrote to the pipe) the read returns the data. `setitimer` with a handler that raises interrupts `os.read`, `select.select`, `epoll.poll` with that exception. A handler that returns does not shorten `select`'s timeout.

### 3.7 Lane-dependent items
Rule for all of them: 3.13 and 3.15 values are below; ticket 0 writes the 3.14 results to `pyjvm-work\n0\measure-314.txt`, and the implementer gates with `LangVersion.atLeast(3, 14)` if 3.14 matches 3.15, else `atLeast(3, 15)`. Nothing else may be guessed.
| Item | 3.13 | 3.15 |
| --- | --- | --- |
| `os.readinto` | absent | present |
| `select.EPOLLWAKEUP` | absent | present |
| `poll.poll(2**40)` | `OverflowError: timeout is too large` | clamps to INT_MAX ms (blocks) |
| `poll.register(fd, -1)` | `ValueError: value must be positive` | `ValueError: Cannot convert negative int` |
| timeout type error, `select.select` | `timeout must be a float or None` | `timeout must be a real number or None, not str` |
| timeout type error, `poll.poll` and `epoll.poll` | `timeout must be an integer or None` | `timeout must be a real number or None, not str` |
| wakeup-fd failure header | `Exception ignored when trying to write to the signal wakeup fd:` | `Exception ignored while trying to write to the signal wakeup fd:` |
| `epoll.register(fd, 2**32)` | accepted silently | accepted with a DeprecationWarning (pyJVM: no warning; recorded gap) |

## 4. Review checklist that applies to every ticket (Opus)
1. **Windows untouched.** No file named `Windows*` is in the diff. In shared files every changed line is (a) inside `if (IoFd.KERNEL)` / `if (!WINDOWS ...)`, (b) a pure visibility change, or (c) explicitly named by the ticket. The body of each existing `IoFd` method below its guard is byte-identical (`git diff -w` shows only added lines there).
2. **Class loading.** No `Posix{Native,Fds,OsFds,Select,SelectModule,ErrnoText}` reference is reachable on Windows: each is behind a `KERNEL`/`!WINDOWS` test, never in a static initialiser of a shared class, never as a field type initialised eagerly. And no `Windows*` reference from the new classes. `test_windows_never_loads_posix_ffm` (ticket 2) passes.
3. **Every syscall checks its result** and builds the error from the captured errno of *that* call (no JVM call between the downcall and `errno(cs)`). errno is read **only after a failing return value**: a successful call does not clear the captured errno, so the segment can hold a stale value (spike, 7.2).
4. **No descriptor leak on error paths** (a successful open/pipe/dup followed by a failure must close what it opened; `adopt` only after success; `forget` before `close`).
5. **No double close** (state is set to closed before `close(2)` is called; a failed close is not retried).
6. **Close-on-exec** on every creating call (1.7).
7. **EINTR and buffer re-entrancy** follow 1.6 exactly (`close`/`dup2`/`dup3` not retried; re-copy after `dispatchPending`).
8. **No placeholders**: nothing returns a made-up value; a function that cannot work is absent.
9. **Method size**: constant tables are data (arrays/strings), not long `put` sequences.
10. The acceptance logs exist for both OSes and the counts in the report match them.

## 5. Tickets

### Ticket 0. WSL environment, per-lane hosts, 3.14 venv, baseline, measurements (no runtime change)
Implementer tier: **sonnet**. Environment and script work with a decided recipe (not haiku: multi-hour gate orchestration and a failing-case inventory that later tickets depend on).
Precondition: B0 exists (the lead fills it in, 1.2). All runs take the run lock (2.2); the four scripts written here acquire it themselves.
Do:
1. WSL: `uv python install --upgrade 3.13 3.14 3.15`. Clone `~/pyjvm-n0` (2.1) at B0. In it: `uv venv --managed-python --python 3.13 .venv313` (same for 3.14, 3.15) and `uv pip install --python .venv31x pytest`. Also add `.venv314` to `~/pyjvm` (HANDOFF open item 2). Note the exact 3.14 patch version (Windows uses 3.14.10 from `C:\Python314`; a different patch level is acceptable but must be written down).
2. `~/pyjvm-work/linux_gates.sh`: make every conformance lane run under its own interpreter and cache, add the 3.14 lane, and take the tree from `PYJVM_TREE` (default `~/pyjvm`). Replace the three lines
   `step conf315 "$PY15" ... --python "$PY15"`, `step conf313 "$PY15" ... --python "$PY13"` with one per lane of the form
   `PYJVM_BUILD_CACHE=$W/bc-conf-31x PYJVM_CACHE=$W/c-conf-31x step conf31x "$PY1x" -m pyjvm315.conformance tests/conformance --python "$PY1x" --selfhosted` (host == reference; the runner refuses otherwise). Logs go to a directory named after the tree and the commit so that a rerun at another commit does not skip steps.
3. `~/pyjvm-work/conf_cases.sh`: lanes `315 314 313`, each `.venv$lane/bin/python -m pyjvm315.conformance ... --python` the same interpreter, per-lane caches, `PYJVM_TREE`, and pass `PYJVM_POSIX_FDS` through unchanged (it is inherited; just do not unset it).
4. Windows: `pyjvm-work\n0\run_cases_n0.ps1` and `run_conf_n0.ps1` = copies of `run_cases.ps1`/`run_conf.ps1` with a `-Tree` parameter (default the worktree), venvs from the main repo, caches under `pyjvm-work\n0\`.
5. Commit to the repo (this is the only repo change): `tools/posix_measure.py` (the measurement script: constants, `dir()` surfaces, signatures, the behaviours of 3.6/3.7; CPython-only, prints one fact per line) and `tools/gen_posix_tables.py` (prints `os.strerror(n)` for 1..133 as a Java string array; parent plan D4 wants generators committed). Run `posix_measure.py` under the three Linux interpreters; store outputs as `pyjvm-work\n0\measure-313.txt`, `-314.txt`, `-315.txt`; diff 314 against both neighbours and list every difference at the end of this plan file (section 7).
6. Baseline at B0, one gate at a time: Linux `PYJVM_TREE=~/pyjvm-n0 linux_gates.sh` (pytest, methodsize, conf315/314/313; strict and probe may be deferred to ticket 5 if time is short, but then say so); Windows conformance three lanes + pytest + methodsize in the worktree. Record pass/fail/skip counts and the **list of failing case names** per lane and OS in `pyjvm-work\n0\baseline.md`.
Acceptance: the three lanes run on Linux without the "host version differs" refusal; `baseline.md` and the three measure files exist; section 7 of this plan is filled in.
Review checklist: every lane's host and `--python` are the same interpreter; caches are per lane and per tree (a shared cache between `~/pyjvm` and `~/pyjvm-n0` would mix runtimes); the baseline was taken at B0 with a clean tree (`git status` in both clones); failing-case lists are names, not only counts; nothing under `runtime/` changed.

### Ticket 1. Spike S1 (scratch only, nothing committed)
Implementer tier: **sonnet**. Small scratch programs against a listed set of checks; nothing lands in the repo, so mistakes are cheap and visible.
Do, in `~/pyjvm-work/n0/spike/` (plain `javac`/`java --enable-native-access=ALL-UNNAMED`, Java 25, no pyJVM code):
1. `open`/`read`/`write`/`close`/`pipe2`/`poll`/`fcntl` through handles built per 1.3 (capture + `firstVariadicArg`), thread-local call state and buffer from `Arena.ofAuto()`, used from two threads.
2. Errors: `open` of a missing file gives errno 2; `read` on a write-only descriptor gives 9; errno survives when the call is made in a loop with allocation between calls.
3. SIGPIPE: write to a pipe whose read end is closed returns -1/EPIPE and the JVM stays alive (U3). If the JVM dies instead: stop and report; the plan then needs an explicit `signal(SIGPIPE, SIG_IGN)` step, which is a design change.
4. `poll` with a 50 ms timeout returns 0 after ~50 ms; `poll` on a closed descriptor returns 1 with POLLNVAL.
5. Throughput: read a 256 MiB file in 64 KiB reads through the thread buffer (downcall + copy into a `byte[]`) against `FileChannel.read` into a heap buffer; three runs each.
6. `malloc`/`free` path: one 16 MiB read.
Output: `pyjvm-work\n0\spike.md` with the numbers.
Acceptance: items 1-4 and 6 behave as stated. Item 5: any ratio is accepted (D1(b) is decided and has no fallback); if FFM is slower than 2x `FileChannel`, add a line to section 7 so that ticket 12 measures end-to-end impact.
Review checklist: the spike used the exact option combination of 1.3; the EPIPE test really closed the read end first; numbers are from WSL, not Windows; nothing was added to the repo.

### Ticket 2. `PosixNative` foundation, strerror table, host check, class-loading test
Implementer tier: **sonnet**. Helper plumbing modelled on the existing `PosixNative`, a generated table and one test; clear and checkable.
Files: `runtime/pyjvm315/runtime/PosixNative.java`, new `PosixErrnoText.java`, `PosixModule.java` (`strerror` only), `tests/test_import_runtime.py`, `THIRD_PARTY_LICENSES` only if the licence test requires an entry for generated data (it is glibc message text read through CPython: treat as facts; add nothing unless `tests/test_licenses.py` objects).
Do:
1. `PosixNative`: add `fn(...)`, `Tl`/`tl()`, `errno(cs)`, `osError(int, String)`, `strerror(int)`, `malloc`/`free` helpers `nativeAlloc(long)`/`nativeFree(MemorySegment)`, package-private `rethrow`. Rewrite `setenv`/`unsetenv`/`fchmod` to use `tl().cs` and `osError` (keep the confined Arena for the two C strings of `setenv`). Delete the private `error()`.
2. `PosixErrnoText`: `static final String[] TEXT` (134 entries, index = errno, from `tools/gen_posix_tables.py` run under Linux CPython) and `static String strerror(int errno)` (negative and above 133 -> `"Unknown error " + errno`; index 0 is `"Success"`, matching CPython's `os.strerror(0)` on Linux, a correction made during implementation). No FFM import in this class. The file header names the generator and the glibc version.
3. `PosixModule.strerror`: replace the ten-case POSIX switch with `return PosixErrnoText.strerror(errno);` (line 169, the Windows early return, stays first).
4. Host check `PosixNative.requireSupportedHost()`: `os.arch` is `amd64` and `defaultLookup().find("gnu_get_libc_version")` is present, else `PyException("ImportError", "pyJVM's POSIX runtime supports Linux x86-64 glibc only (found <os.name>/<os.arch>)")`. Not called yet (ticket 3 calls it).
5. `tests/test_import_runtime.py`: new `test_windows_never_loads_posix_ffm` (skip unless Windows): run a program that imports `os, io, select, signal, subprocess`, opens/reads/writes a temp file and calls `os.pipe()`, with `JDK_JAVA_OPTIONS=-Xlog:class+load:file=<tmp>`; assert the log contains none of `pyjvm315.runtime.PosixNative`, `PosixFds`, `PosixOsFds`, `PosixSelect`, `PosixSelectModule`, `PosixErrnoText`. (`PosixModule` and `PosixFiles` are shared and are expected.) The launcher prints a "Picked up JDK_JAVA_OPTIONS" note on stderr: do not compare stderr.
Behaviour change on POSIX (intended): `os.strerror(n)` and java.nio-derived messages now use the full glibc text for every errno instead of "Unknown error N" outside ten cases.
CPython reference: `os.strerror(n)` for 1..134 (3.6: 134 -> `Unknown error 134`).
Acceptance: L-min in default mode on three lanes, plus `oserror_errno_subclass`, `oserror_aliases`, `os_putenv_child` unchanged from baseline; a one-off check script under pyJVM on Linux printing `[os.strerror(i) for i in range(1, 135)]` equal to CPython's; W-min; the new Windows test passes.
Review checklist: `os.strerror` does not initialise `PosixNative` (its static handles): `PosixModule.strerror` calls `PosixErrnoText` directly; `setenv`/`unsetenv`/`fchmod` raise the same types and texts as before for errno 1, 2, 13, 17, 20, 21, 22; the thread-local segments come from `Arena.ofAuto()` (a confined or shared-closed arena would break other threads or leak); `rethrow` never wraps a `PyException`; the Windows test really fails if a `Posix*` FFM class is loaded (ask the implementer to show it failing with a deliberate reference, then reverted); table is an array, methodsize still 0 over.

### Ticket 3. `PosixFds` core and the `IoFd` dispatch (opt-in)
Implementer tier: **opus**. The engine under all POSIX file I/O: raw native memory (buffer growth, malloc/free), errno capture, the re-entrancy rule and short-read/short-write semantics, where a mistake corrupts data silently.
Files: new `PosixFds.java`; `IoFd.java`; `IoFile.java` (two call sites); `IoBuffered.java` (hint only); new `tests/test_posix_fds.py`.
Do:
1. `IoFd`: add `static final boolean KERNEL` (1.2, opt-in form) and, when true, call `PosixNative.requireSupportedHost()` from the static initialiser. Guards, first line of each method:
   | `IoFd` method | kernel implementation |
   | --- | --- |
   | `open(path, flags, mode)` (after the existing `IoUtil.syncAll()` line) | `PosixFds.open` |
   | `close(fd)` | `PosixFds.close` |
   | `dup(fd)` | `PosixFds.dup` |
   | `read(fd, n)` | `PosixFds.read` |
   | `write(fd, b, off, len)` | `PosixFds.write` |
   | `seek(fd, pos, how)` | `PosixFds.lseek` |
   | `tell(fd)` | (falls through to `seek`) |
   | `seekable(fd)` | `PosixFds.seekable` |
   | `size(fd)` | `PosixFds.size` |
   | `truncate(fd, size)` | `PosixFds.ftruncate` |
   | `sync(fd)` | `PosixFds.fsync` |
   | `isatty(fd)` | `PosixFds.isatty` |
   | `valid(fd)` | `PosixFds.valid` |
   | `get(fd)` | `throw new IllegalStateException("IoFd.get in kernel mode")` (callers are migrated; this catches a missed one) |
   | `flushProcessStreams()` | unchanged (harmless) |
   New: `static void checkOpen(long fd)` (kernel: `PosixFds.valid` else `badFd()`; otherwise `get(fd)`), `static boolean isDir(long fd)` (kernel: `PosixFds.isDir`; otherwise `get(fd).dir`).
2. `PosixFds` (all take `long fd`, convert with `cfd`):
   - `open`: `open(pathBytes, (int) (flags & 0xffffffffL & ~IoFd.DELETE_ON_CLOSE) | O_CLOEXEC, (int) mode)`; EINTR retry; error with the caller's path as filename; `adopt(fd)` on success. No pre-checks of any kind (the kernel decides EISDIR/ENOTDIR/EEXIST).
   - `close`: `forget(fd)` then `close(fd)`; error -> `osError(errno, null)`; never retried.
   - `dup`: `fcntl(fd, F_DUPFD_CLOEXEC, 0)`; `adopt`.
   - `read(fd, n)`: `n <= 0` -> empty; `awaitReady` (1.6) ; **one** `read(2)` of `n` bytes into the thread buffer (or malloc above 1 MiB); EINTR retry; EAGAIN and every other error -> `osError`; copy out exactly the returned count. A short read is returned as is (no loop).
   - `write(fd, b, off, len)`: `awaitReady(POLLOUT)`; copy in; **one** `write(2)`; EINTR -> `dispatchPending`, re-copy, retry; returns the count (may be short).
   - `lseek(fd, pos, how)`: passes `how` through; errors from errno. `seekable(fd)`: `lseek(fd, 0, SEEK_CUR) >= 0`, no exception. `size(fd)`: `fstat` `st_size`. `ftruncate`, `fsync`: direct, EINTR retry.
   - `isatty(fd)`: `isatty(fd) == 1` (never raises). `valid(fd)`: fits an int and `fcntl(fd, F_GETFD, 0) != -1`. `isDir(fd)`: `fstat` and `(st_mode & 0170000) == 0040000`.
   - `fstatRaw(int fd, MemorySegment st)` shared helper (ticket 4 builds the Python object on it).
   - `adopt`/`forget`/`gen` (1.7), used from this ticket on.
3. `IoFile.init`: `IoFd.get(fd)` (line 97) -> `IoFd.checkOpen(fd)`; `IoFd.Fd e = IoFd.get(fd); if (e.dir)` (lines 106-107) -> `if (IoFd.isDir(fd))`. No other change.
4. `IoFile.readall` and `IoBuffered.readAll`: when the size/tell hint throws, the fallback chunk becomes 65536 in kernel mode (pipes), 8192 otherwise (`long hint = IoFd.KERNEL ? 65536 : 8192;`).
5. `tests/test_posix_fds.py` (skip unless Linux): helper that runs a script under pyJVM with a given environment (copy the launch helper of `tests/test_import_runtime.py`). Tests with `PYJVM_POSIX_FDS=kernel`: (a) `fd = os.open("/dev/null", os.O_RDONLY); os.readlink("/proc/self/fd/%d" % fd) == "/dev/null"` (proves the number is the kernel's); (b) write/seek/read/truncate round trip through `open()` in text and binary modes; (c) the same script's stdout equals CPython's.
What still goes through the old code in kernel mode after this ticket (fixed in ticket 4): `PosixFiles` functions that call `IoFd.get` (`fstat`, `fchmod`, `get_inheritable`, `set_inheritable`, `get_blocking`, `set_blocking`) raise the IllegalStateException. Therefore this ticket's kernel-mode acceptance excludes cases that call them (`io_posix` calls `fstat`). Also `SelectModule` (`valid` then `get`) raises it in kernel mode until ticket 10 (found in ticket 3's review). Recorded at ticket 3: on an unsupported host `requireSupportedHost` throws inside `IoFd`'s static initialiser, so the user sees `ExceptionInInitializerError` wrapping the ImportError rather than a clean ImportError (accepted; kernel mode is opt-in until ticket 5, revisit there). Ticket 3 also changed `IoFile.init` beyond the two call sites: the `isDir` check after an open closes the new descriptor if `fstat` itself raises (review finding: fd leak).
CPython reference: 3.6 "Descriptors" lines for open/read/write/lseek/ftruncate/fsync/close/dup/append.
Acceptance: default mode: L-min three lanes identical to baseline; W-min. Kernel mode (`PYJVM_POSIX_FDS=kernel`): `tests/test_posix_fds.py`; conformance `io_files, io_memory, encoding_defaults, io_codecs_basic, os_putenv, os_platform` on three lanes; plus a scratch script (kept in `pyjvm-work\n0\t3_check.py`, compared against CPython by hand) covering the 3.6 lines above that need no `fstat`.
Review checklist:
- fd leak: `open` returning a descriptor and then throwing (e.g. in `adopt` or path handling after the call) cannot happen; the path Arena is closed in `finally`; `IoFile.init` still closes a descriptor it opened when the directory check fires.
- double close: `forget` precedes `close`; `IoFile.close` clears `s.fd` before calling `IoFd.close` (unchanged, verify not regressed).
- close-on-exec: `O_CLOEXEC` is OR-ed in unconditionally; `dup` uses `F_DUPFD_CLOEXEC`, not `dup(2)`.
- O_APPEND: no Java-side append handling in kernel mode (`f.append` logic must not be mirrored); `IoFile.init`'s seek-to-end for mode `a` remains and is correct.
- short reads/writes: `PosixFds.read`/`write` do not loop; check `IoBuffered.read`, `fill`, `flushWrite`, `writeBytes` still terminate and deliver all bytes with a raw stream that returns short counts (they loop already; confirm by reading, and by a pipe test in ticket 6).
- EINTR/re-entrancy per 1.6: the write path re-copies after `dispatchPending`; nothing dispatches between `read(2)` and copy-out.
- the buffer: growth replaces the thread-local segment (no use of a stale reference after growth); the malloc path frees in `finally`; `MemoryError` on NULL.
- `flags` masking: `IoFd.DELETE_ON_CLOSE` (bit 40) never reaches `open`; `mode` is passed unmasked (the kernel applies the umask).
- relative paths honour the emulated cwd (test: `os.chdir(d); open("x", "w")` creates `d/x`), and the error filename is the caller's string.
- std streams: in kernel mode `print()` output still appears, in order with stderr, and is flushed at exit (run a script that prints without a trailing newline and exits; one that raises).
- `KERNEL` is false on Windows whatever the environment variable says.

### Ticket 4. `os` descriptor functions on the kernel: fstat, fchmod, flags, ids, umask (opt-in)
Implementer tier: **sonnet**. A table of small independent functions over helpers that exist after ticket 3; each is one or two syscalls with a measured reference.
Files: new `PosixOsFds.java`; `PosixFiles.java` (one line at the end of `install`: `if (IoFd.KERNEL) PosixOsFds.install(m);`); `PosixFds.java` (helpers); `SelectModule.java` (one interim line, below).
`PosixOsFds.install(m)` re-registers (overwrites the shared lambdas; `AuditEvents.install` runs afterwards and wraps whichever is registered, so `fchmod` keeps its `os.chmod` event):
| Function | Implementation |
| --- | --- |
| `close`, `read`, `write`, `lseek`, `ftruncate`, `fsync`, `dup`, `isatty` | same calls as the shared lambdas but the descriptor goes through `PosixFds.cfd` (OverflowError rule); `isatty` = `PosixFds.isatty` only |
| `fstat(fd)` | `PosixFds.fstat`: build the result with `PosixModule.newStatResult()` + `PosixModule.fill(r, mode, ino, dev, nlink, uid, gid, size, {atime_ns, mtime_ns, ctime_ns})` + `put` of `st_rdev`, `st_blksize`, `st_blocks` from the struct (3.2). No `/proc` path, no `IoUtil.syncAll()` (a descriptor's size is the kernel's) |
| `fchmod(fd, mode)` | `checkFchmodArgs`, then `PosixNative.fchmod` for every descriptor |
| `fdatasync(fd)` | `fdatasync(2)` |
| `get_inheritable(fd)` | `(fcntl(F_GETFD) & FD_CLOEXEC) == 0`; error -> `osError` |
| `set_inheritable(fd, b)` | read `F_GETFD`, set or clear `FD_CLOEXEC`, `F_SETFD` only if it changes |
| `get_blocking(fd)` | `(fcntl(F_GETFL) & O_NONBLOCK) == 0` |
| `set_blocking(fd, b)` | read `F_GETFL`, set or clear `O_NONBLOCK`, `F_SETFL` only if it changes |
| `umask(mask)` | libc `umask((int) mask)`, returns the old value |
| `getuid`, `geteuid`, `getgid`, `getegid` | libc, unsigned |
| `getppid` | libc |
| `get_terminal_size(fd=1)` | `ioctl(fd, TIOCGWINSZ, &ws)`; result `os.terminal_size((ws_col, ws_row))` built the way `WindowsNtModule.terminalSize` builds it (read that method; do not call it); error -> `osError` (pipe: errno 25) |
Interim fix in `SelectModule.ready` (the old POSIX select code must keep working in kernel mode until ticket 10 replaces it, because ticket 5 flips the default first): after the `IoFd.valid` test insert `if (IoFd.KERNEL) return fd == 0 ? (stdinReady() ? 1 : 0) : fd <= 2 ? 2 : 3;` so that `IoFd.get` is not reached. After this ticket no code path reachable on POSIX in kernel mode calls `IoFd.get` (grep; the remaining callers are `WindowsNtModule`, `WindowsMsvcrtModule` and nio-mode branches).
Not changed: `getpid` (ProcessHandle is correct), `device_encoding` (returns None; CPython returns the locale encoding for a tty: recorded gap, not N0), `os.open(dir_fd=...)` (still NotImplementedError), `chdir`.
CPython reference: 3.6 "Descriptors".
New conformance case `tests/conformance/os_fd_flags.py` (first line unconditional `print(os.name)`, the rest under `if os.name == "posix":`): inheritable flag of `os.open`/`os.dup`, `set_inheritable` round trip, `get_blocking`/`set_blocking` round trip on a regular file, `fstat` of a file (size after write without flush through a second descriptor, `S_ISREG`, `st_nlink`, `st_ino == os.stat(path).st_ino`, `st_dev` likewise, `st_mtime_ns` type) and of a directory descriptor, `fchmod` of a descriptor whose file was **renamed** and one whose file was **unlinked** (works: this is the divergence the old path-based code documented), `ftruncate`/`fsync`/`fdatasync`, the bad-descriptor and OverflowError lines of 3.6, `os.umask` round trip (restore it), `os.getuid() == os.geteuid()` printed only as types/consistency (`os.getuid() == os.stat(tmpfile).st_uid`), `os.getppid() > 0`, `os.get_terminal_size` on a file descriptor -> errno 25, `os.isatty(9999)` False. Nothing host-specific is printed.
Acceptance: kernel mode: `os_fd_flags`, `io_posix`, `os_fchmod`, `os_terminal_size` and L-min on three lanes; default mode: L-min equals baseline (and `os_fd_flags` is expected to fail there: say so in the report); W-min plus `os_fd_flags` on Windows (prints `nt` on both).
Review checklist:
- every `fcntl` getter result is checked for -1 before use as a bit set (a -1 treated as flags sets every bit);
- `set_inheritable`/`set_blocking` preserve the other flag bits (read-modify-write, not overwrite);
- `st_uid`/`st_gid`/`st_mode` are read as unsigned 32-bit; times are `sec * 1_000_000_000 + nsec`; `st_dev`, `st_ino` unsigned (a value above 2^63 must not print negative: `Long.toUnsignedString` -> BigInteger if the runtime's int type requires it; check how `PosixModule.stat` treats large inodes and match it);
- the stat buffer is 144 bytes and not shared across a `dispatchPending()`;
- `fstat` on 0/1/2 no longer goes through `/proc/self/fd`; `fchmod` no longer has the ENOENT/ESTALE path in kernel mode, and the nio-mode code is unchanged;
- `umask` is not cached anywhere in kernel mode (`IoFd.umask()` must be unreachable there: grep);
- `PosixFiles.install` gained exactly one line; on Windows `KERNEL` is false so `nt` is built exactly as before (W-min shows `os_platform`, `os_pipe` unchanged);
- no leftover placeholder: `get_inheritable` returning a constant, `set_blocking` no-op must be overwritten in kernel mode (list `PosixOsFds.install` names against the table above).

### Ticket 4b. Path stat on the kernel: `os.stat`, `os.lstat`, `DirEntry.stat`/`inode` agree with `os.fstat` (opt-in)
Implementer tier: **sonnet**. One dispatch line and one FFM call on the struct layout ticket 4 already reads; the spec is fully decided.
Why here: after ticket 4 (which owns the struct reader) and before the flip, so ticket 5's full regression covers it. Removes the disagreement between a java.nio `stat` (which invents `st_blksize` 4096 and `st_blocks = (size + 511) / 512` and maps unknown errors to errno 5) and the kernel `fstat`.
Files: `PosixModule.java` (`stat` only), `PosixFds.java`, `PosixOsFds.java`, new `tests/conformance/os_stat_agree.py`.
Do:
1. `PosixFds.pathBytes` takes the function name for its NUL message: `pathBytes(String func, String path)` -> `ValueError(func + ": embedded null character in path")`; ticket 3's caller passes `"open"`.
2. `PosixFds.statPath(String func, String path, boolean follow)`: `fstatat(AT_FDCWD, pathBytes(func, path), st, follow ? 0 : AT_SYMLINK_NOFOLLOW)`; EINTR retry; error -> `PosixNative.osError(errno, path)` (the caller's string); result built by the **same** private builder `PosixFds.statResult(MemorySegment st)` that ticket 4's `fstat` uses (refactor ticket 4's code into it; there must be one place that turns a `struct stat` into a `stat_result`).
3. `PosixModule.stat(String path, boolean follow)`: after the existing `IoUtil.syncAll();` and the Windows line, insert `if (IoFd.KERNEL) return PosixFds.statPath(follow ? "stat" : "lstat", path, follow);`. This one line covers every consumer: `os.stat`, `os.lstat`, `DirEntry.stat`, `DirEntry.inode` (`PosixFiles.deStat`, `deInode`) and any internal caller. `IoUtil.syncAll()` stays first (pending buffered writers are flushed before a path stat; existing pyJVM behaviour, see `IoUtil.java:68-72`).
4. `os.stat(fd)`: in `PosixOsFds.install`, re-register `stat` so that an `int` first argument (`True` counts as fd 1, as in CPython) returns `PosixFds.fstat(cfd(fd))`, with the error's `filename` attribute set to the int (build the error with `PosixNative.osError(errno, null)` and put the Long under `filename` the way `PosixModule.osError(IOException, String, String)` adds `filename2`); `follow_symlinks=False` keyword -> `PosixModule.stat(path, false)`; `dir_fd` not None -> `NotImplementedError("os.stat: dir_fd is not supported")` (same policy as `os.open`); a float or other non-path -> the TypeError text of 3.6. Re-register `lstat` only to add the TypeError and NotImplementedError rules; an int there is the TypeError of 3.6. Everything else falls through to `PosixModule.stat`.
5. Not moved in this ticket (they return booleans or names, so they cannot disagree with `fstat` on a field): `access`, `listdir`, `scandir`'s listing, `DirEntry.is_dir/is_file/is_symlink`, `chmod`, `utime`, `chdir`'s existence checks. `IoFd.fileKey` is nio-mode only.
CPython reference: 3.6 "Path stat".
New conformance case `tests/conformance/os_stat_agree.py` (posix-gated after an unconditional `print(os.name)`): the 3.6 "Path stat" lines; plus `os.stat` of a relative path after `os.chdir(tmp)`; a bytes path (print only the size); `os.path.samefile`/`samestat` between a path and a descriptor; a file extended with `os.ftruncate(fd, 1 << 20)` (sparse: `st_blocks` equality between `stat` and `fstat` is the line the old code fails); a symlink loop -> errno 40. Do not print error filenames for bytes paths (pyJVM turns them into `str`; existing gap, not N0's).
Acceptance: kernel mode: `os_stat_agree`, `io_posix`, `os_fd_flags`, `os_fchmod`, `os_platform` and L-min on three lanes, plus `tests/test_import_runtime.py` and `tests/test_stdlib_jar.py` on Linux under `PYJVM_POSIX_FDS=kernel` (importlib stats every path it probes: this is the hot path); default mode: L-min equals baseline; W-min plus `os_stat_agree` on Windows (prints `nt`).
Review checklist:
- one builder for `struct stat` -> `stat_result` (grep: `fstat` and `statPath` both call it); unsigned handling as in ticket 4;
- `PosixModule.stat` gained exactly one line, placed after the `WINDOWS` return, so `WindowsNtModule.stat` is still what Windows runs; the java.nio body below is untouched for nio mode;
- relative paths use the emulated cwd through `pathBytes` (string join, no normalisation) and the error filename is the caller's string, not the joined one;
- `lstat` really passes `AT_SYMLINK_NOFOLLOW` (the dangling-link and symlink lines in the case);
- errors come from errno (ENOTDIR 20, ELOOP 40, ENAMETOOLONG 36, EACCES 13), none collapses to errno 5;
- the path Arena is confined and closed; the 144-byte buffer is not reused across a `dispatchPending()`;
- performance: importlib's path finder calls `stat` thousands of times at start-up; the implementation makes one downcall per `stat` (no exists pre-check, no second syscall for the symlink case);
- `os.stat(True)` is fd 1, `os.stat(1.5)` is the TypeError; `os.lstat(3)` is the TypeError;
- no descriptor is created (nothing to leak).

### Ticket 5. Flip the default; full regression on both OSes
Implementer tier: **sonnet**. The code change is one expression, but the work is running full gates on two OSes under the lock, comparing by case name and applying the stop line (not haiku: differences must be recognised and reported correctly).
Files: `IoFd.java` (the `KERNEL` expression, 1.2), `tests/test_posix_fds.py` (drop the explicit `kernel` setting; add one test that `PYJVM_POSIX_FDS=nio` still runs the round-trip script).
Do: flip; run L-full (three lanes, pytest, methodsize, strict lane) and W-full; compare with `baseline.md`. Also time the cold build of one lane's first conformance case before/after (the self-hosted compiler reads and writes many files: this is the D1(b) cost check), three runs each, and record it.
Acceptance: Linux: every case that passed at baseline passes; the failing set is the baseline's or smaller, apart from the cases named in tickets 3, 4 and 4b that now pass (`os_fd_flags`, `os_stat_agree`). Windows: identical pass/fail sets to baseline on three lanes, pytest and methodsize unchanged. Cold-compile wall time within +15% of baseline on Linux; if worse, stop and report with the numbers (the remedy, a larger thread buffer or fewer `poll` pre-waits, is a design decision for the planner, not the implementer).
Review checklist: the diff is the one expression plus tests; the comparison is by case name; the strict lane was run on Linux (it exercises `tempfile`, `shutil`, large stdlib I/O); the `nio` rollback was exercised once on Linux (L-min under `PYJVM_POSIX_FDS=nio`); logs show the runs were sequential; hs_err files absent.

### Ticket 6. New descriptor API: pipe, pipe2, dup2, pread, pwrite, closerange, readinto, constants
Implementer tier: **sonnet**. New functions on the ticket 3 helpers with exact measured semantics; the threaded pipe case catches mistakes.
Files: `PosixOsFds.java`, `PosixFds.java`, `tests/conformance/os_pipe.py`.
Do (all registered in `PosixOsFds.install`, so only in kernel mode):
- `pipe()`: `pipe2(fds, O_CLOEXEC)`; `adopt` both; returns `(r, w)`.
- `pipe2(flags)`: `pipe2(fds, flags)` verbatim (no forced `O_CLOEXEC`: CPython passes the caller's flags; measured `pipe2(0)` is inheritable).
- `dup2(fd, fd2, inheritable=True)` (positional or keyword): `inheritable` true -> `dup2(fd, fd2)`; false -> `dup3(fd, fd2, O_CLOEXEC)`. On success `adopt(fd2)` (new generation) unless `fd == fd2`. Returns `fd2`. Not retried on EINTR.
- `pread(fd, length, offset)`, `pwrite(fd, data, offset)`: as `read`/`write` (same buffer policy and 1.6 rules, no `awaitReady` since they apply to seekable files), negative length -> `OSError(22)` before the call as `read` does.
- `closerange(low, high)`: both through `cfd`; for each `fd` in `[max(low, 0), high)` capped at the process's highest possible descriptor: iterate only over the numbers listed in `/proc/self/fd` that fall in the range (a loop to 2^31 is not acceptable), `forget` + `close`, errors ignored.
- `readinto(fd, buffer)` (lane rule 3.7): read up to `len(buffer)` into it with `IoUtil.fill`, return the count.
- Constants added to the module in kernel mode: `O_ACCMODE`, `O_ASYNC`, `O_DIRECT`, `O_NOATIME`, `O_PATH`, `O_TMPFILE`, `O_RSYNC`, `O_FSYNC`, `O_LARGEFILE`, `SEEK_DATA`, `SEEK_HOLE` (3.3).
- `os_pipe.py`: keep the Windows branch byte-identical. Change the header comment and add `elif os.name == "posix":` with: round trip; two writes then a 4-byte and a 100-byte read; `fstat` (`S_ISFIFO`, size 0, `isatty` False); EOF after the last writer closes (through a `dup`); `dup2` redirect, `dup2(r, r)`, `dup2(9999, 5)` EBADF, `dup2(fd, fd, inheritable=False)` EINVAL; inheritable flags of `pipe`, `dup`, `dup2` default and `inheritable=False`, `pipe2(0)`, `pipe2(O_NONBLOCK | O_CLOEXEC)`; `set_blocking(r, False)` then `os.read` -> `BlockingIOError` errno 11; `io.open` on pipe ends in text and binary (the Windows block's lines 49-62 are the model); closed-descriptor EBADF lines; write with the reader closed -> `BrokenPipeError 32`; `pread`/`pwrite` on a temp file not moving the offset; `closerange`; a 200 000-byte write from a thread with the main thread reading until EOF (short reads and partial writes through `os.read`/`os.write` and through `io.open(r, "rb")`), printing only the total and a checksum.
CPython reference: 3.6.
Acceptance: `os_pipe` and `os_fd_flags` on Linux three lanes; `os_pipe` on Windows three lanes (unchanged output); L-min, W-min; a pytest in `tests/test_posix_fds.py` that under `PYJVM_POSIX_FDS=nio` `hasattr(os, "pipe")` is False (the old state, nothing half-registered).
Review checklist:
- fd leaks: `pipe2` failure leaves nothing; both ends adopted; a failed `dup2` does not `adopt`.
- double close: `closerange` uses `forget` + `close` per number and ignores EBADF; it never closes numbers outside the range and never loops over the full int range.
- close-on-exec: `pipe()` is `O_CLOEXEC`; `dup2` default really is inheritable (CPython semantics, not a bug) and `inheritable=False` uses `dup3`, not `dup2` + `fcntl` (not atomic).
- `dup2` onto an open descriptor: the generation of `fd2` changes, so a stale finalizer for the old object behind `fd2` will skip (ticket 7 relies on this).
- `pipe2` passes flags unmodified; invalid flags surface the kernel's EINVAL.
- the threaded 200 000-byte case passes repeatedly (run it 5 times): it is the test for short reads through `IoBuffered`.
- `os_pipe.py`'s Windows branch is textually unchanged (`git diff` shows only the comment and the new branch).
- SIGPIPE: the `BrokenPipeError` line passes under pyJVM (if the JVM died, ticket 1 was wrong: stop).

### Ticket 7. Finalizer close guard (descriptor generations)
Implementer tier: **opus**. Concurrency and finalizer timing in code shared with Windows (`GcFinalizable`, `IoFile`); a wrong ordering closes someone else's descriptor with no error.
Files: `GcFinalizable.java`, `IoFile.java`, `PosixFds.java` (if anything is missing from ticket 3).
Do:
1. `GcFinalizable`: `private static final ThreadLocal<int[]> DEL_DEPTH = ThreadLocal.withInitial(() -> new int[1]);` and `static boolean inDel() { return DEL_DEPTH.get()[0] > 0; }`. In the task's `run()` wrap the existing two statements that fetch and call `__del__`: `int[] d = DEL_DEPTH.get(); d[0]++; try { ... } finally { d[0]--; }`. Nothing else in that file changes. (Shared with Windows: it only counts; no behaviour depends on it there.)
2. `IoFile.St`: new `long gen`. In `init`, after the descriptor is final: `s.gen = IoFd.KERNEL ? PosixFds.gen(fd) : 0;`.
3. `IoFile.close`: in the `finally`, replace `if (s.closefd) IoFd.close(fd);` by: `if (s.closefd && !(IoFd.KERNEL && GcFinalizable.inDel() && PosixFds.gen(fd) != s.gen)) IoFd.close(fd);`. The same condition in `init`'s re-initialisation close (line 62).
Semantics: an explicit `close()` always closes the number (CPython semantics). A close reached from any `__del__` closes only if the number still denotes the descriptor this object was created on. A descriptor never adopted (generation 0 at `init`, e.g. inherited from the parent) and still unknown closes as before.
New pytest in `tests/test_posix_fds.py`: script: `fd = os.open(p1, ...)`; `f = os.fdopen(fd, "rb")`; `os.close(fd)`; `fd2 = os.open(p2, ...)` (asserts `fd2 == fd`); `del f; gc.collect()`; then `os.fstat(fd2)` must succeed and reading `fd2` returns p2's content. And the plain case: `f = open(p1); del f; gc.collect()` -> the descriptor is closed (`os.fstat(old)` raises EBADF).
CPython reference: in CPython the stale object's close fails immediately with EBADF (ignored in `__del__`); the observable requirement is only that `fd2` survives.
Acceptance: the two pytests; L-min, W-min; `tests/test_threads_stress.py` and the weakref/finalizer conformance cases (`grep -l "__del__\|gc.collect" tests/conformance`) on Linux 3.15 and Windows 3.15 unchanged.
Review checklist:
- finalizer timing: the depth counter is incremented on the thread that runs `__del__` (the creating thread at its poll point), balanced in `finally`, and nested finalizers work (counter, not boolean);
- `forget` happens before `close(2)` everywhere (`PosixFds.close`, `closerange`); `adopt` after every creation (`open`, `dup`, `pipe`, `pipe2`, `dup2` target); list them;
- a buffered/text wrapper's `__del__` closing its raw `FileIO` goes through the same condition (the depth is thread-wide, so yes: confirm with a `open(p, "w")` object dropped unclosed);
- the guard can only *skip* a close, never add one; in nio mode and on Windows the condition reduces to the old one;
- no generation is compared for `closefd=False` objects.

### Ticket 8. Signals: pre-wait slicing, EINTR dispatch, POSIX wakeup fd
Added at ticket 3 (found in the L-min runs): pyJVM installs `default_int_handler` for SIGINT even when the process inherited SIGINT as SIG_IGN; CPython keeps SIG_IGN (`signal.getsignal(SIGINT)` returns `Handlers.SIG_IGN`, Ctrl-C ignored). Read the inherited disposition at start-up (`sigaction(SIGINT, NULL, &old)`) and match CPython; test by launching under `perl -e '$SIG{INT}="IGNORE"; exec @ARGV'`.
Implementer tier: **opus**. Signal delivery across threads, re-entrant Python handlers inside I/O calls and timing-dependent tests; failures are hangs or lost signals.
Files: `PosixFds.java` (`awaitReady` if ticket 3 stubbed it; it must be complete here), `SignalModule.java`.
Do:
1. `SignalModule.isMain()` becomes package-private. `awaitReady` exactly as 1.6 (if ticket 3 already implemented it fully, this ticket only adds the tests).
2. `setWakeupFd`, POSIX branch (replace the `NotImplementedError` at line 368 when `IoFd.KERNEL`; keep it in nio mode): for `fd != -1`: `fstat` fails -> `osError(errno, null)`; blocking (`F_GETFL` without `O_NONBLOCK`) -> `ValueError("the fd " + fd + " must be in non-blocking mode")`; then `synchronized (LOCK) { old = wakeupFd; wakeupFd = fd; wakeupWarn = warn; } return old;` (same fields as Windows).
3. `deliver`: `if (WIN) writeWakeup(sig);` becomes `if (WIN) writeWakeup(sig); else if (IoFd.KERNEL) writeWakeupPosix(sig);`, with a new `private static void writeWakeupPosix(int sig)` in `SignalModule` that returns at once when `wakeupFd == -1`, otherwise reads `wakeupFd`, calls `PosixFds.writeByteNoThrow(fd, sig)` (one `write(2)` of one byte, no `awaitReady`, no retry, returns 0 or errno) and, when the result is non-zero and (`wakeupWarn` or errno != EAGAIN), prints to `System.err`: the lane's header line (3.7) + `"\n"` + `ErrnoModule.osErrorType(errno) + ": [Errno " + errno + "] " + strerror + "\n"`, then flushes. The existing Windows `writeWakeup` is not edited.
4. `deliver` runs on arbitrary threads (the JVM signal dispatcher, the itimer thread): `writeByteNoThrow` must use that thread's own `Tl`.
New conformance case `tests/conformance/signal_blocking_io.py` (posix-gated as usual): (a) main thread in `os.read(r, 100)`, a thread sleeps 0.2 s then `signal.pthread_kill(threading.main_thread().ident, signal.SIGUSR1)`; the handler writes to `w`; print the data and the recorded signal; (b) `signal.setitimer(ITIMER_REAL, 0.2)` with a handler raising `TimeoutError("alarm")` around `os.read(r, 1)`, around `sys.stdin`-free `io.open(r2, "rb").read(1)`, and around `os.write` to a full blocking pipe; (c) a handler that returns, then data arrives: the read returns the data; (d) `set_wakeup_fd`: blocking fd -> ValueError text; bad fd -> errno 9; non-blocking pipe: `raise_signal(SIGUSR1)` then `os.read(r3, 10) == bytes([SIGUSR1])`; return values `-1` then the previous fd; (e) non-blocking descriptor on the main thread with a handler installed: `os.read` -> `BlockingIOError` immediately (elapsed < 0.05 s printed as a bool). stderr is not part of the comparison, so the full-buffer warning is exercised in a pytest that checks the header line per lane.
CPython reference: 3.6 "Signals", 3.7.
Acceptance: `signal_blocking_io`, `signal_basic`, `select_files` on Linux three lanes; the pytest; L-min; W-min plus the Windows asyncio/overlapped cases that use the wakeup socket (`asyncio_run_timers` and the other `asyncio_*`, `overlapped_*` cases) on the 3.15 lane, unchanged.
Review checklist:
- the non-blocking check happens only after the first empty slice, and a non-blocking descriptor never waits a second slice;
- `dispatchPending()` is called only from the pre-wait and the EINTR branches, never while the thread buffer holds unreturned data; the write path re-copies;
- a handler exception leaves no partial state (nothing was read or written yet);
- off the main thread, or before `_signal` is imported (`SignalModule.active` false), there is no `poll` at all (performance: check with `strace -c` on a file-copy script, or by reading);
- `close` is not routed through any retry;
- the Windows `writeWakeup` and the `WIN` branch of `setWakeupFd` are textually unchanged; `MAIN`/`active` semantics unchanged;
- wakeup write uses `write(2)` (not `send`), one byte, value = signal number, and the EAGAIN/warn rule matches CPython's (`warn || errno != EAGAIN`);
- case (b) with `os.write` on a full pipe: passes because the pre-wait precedes the write (if it hangs, the pre-wait is missing on the write path).

### Ticket 9. Non-blocking semantics in `_io`
Implementer tier: **opus**. Subtle silent-failure semantics (EOF against would-block, byte accounting in `characters_written`) in the buffered layer that Windows shares.
Files: `IoFd.java`, `PosixFds.java`, `IoFile.java`, `IoBuffered.java`; `IoText.java` only if step 5 finds a crash.
Do:
1. `IoFd.readOrNull(long fd, int n)`: kernel -> `PosixFds.readOrNull` (as `read`, but EAGAIN returns null); otherwise `return read(fd, n);`. `IoFd.writeOrNeg(long fd, byte[] b, int off, int len)`: kernel -> `PosixFds.writeOrNeg` (EAGAIN returns -1); otherwise `return write(...)`.
2. `IoFile`: `read(size)`: null -> return `None` (Java `null`). `readall`: on null, return `None` if nothing was accumulated, else the bytes so far. `readinto`: null -> `None`. `write`: -1 -> `None`. These four switch from `IoFd.read`/`write` to the new calls.
3. `IoBuffered.FileOps.read` -> `IoFd.readOrNull` (may return null); `FileOps.write` -> `IoFd.writeOrNeg`. `ObjOps` is **not** changed (it keeps turning a raw `None` into `b''` and raising on a `None` write; changing it would alter Windows behaviour).
4. `IoBuffered`, handling a null from `ops.read` (a "would block"):
   - `fill(b)`: returns -1 (and leaves `rbuf` empty) on null; 0 still means EOF.
   - `read(n)`: stop the loop on would-block; return `None` if nothing was accumulated in this call, else the accumulated bytes. `readAll`: the same (`None` if nothing, else partial). `readIntoImpl`: a `None` from `read`/`read1` -> return `None`.
   - `read1`: would-block -> `b''`. `peek`: -> `b''`. `readline`: would-block ends the loop and returns what it has (possibly `b''`).
   - `flushWrite(b)` gets a boolean result instead of throwing: true when the buffer was emptied, false when the raw write would block (-1); the existing `finally` bookkeeping is kept. Callers other than `writeBytes` (`flush`, `close`, `fill`, `read*`, `seek`, `truncate`, `flushQuietly`) go through a wrapper `flushOrRaise(b)` that throws the would-block error (step 6) with `characters_written = 0` on false; `flushQuietly` ignores the result.
   - `writeBytes(b, d)`: if a flush or a direct raw write would block: copy as much of the not-yet-consumed part of `d` as fits into the free space of `wbuf` (register dirty as usual) and throw the would-block error with `characters_written` = the number of bytes of `d` consumed so far (written to the raw stream or accepted into the buffer). Measured anchor: buffer 16 holding 8, writing 25 bytes -> 8.
5. `IoText`: find how it reads from its buffer (`read1` or `read`); if it can receive `None`, treat it as `b''` at that one call site so that `read(5)`/`readline()` return `''` (measured).
6. The error: `PosixModule.osError("BlockingIOError", 11, "write could not complete without blocking", null)` with an extra attribute `characters_written` (long) added the way `PosixModule.osError(IOException, String, String)` adds `filename2` (copy the attrs map, build a new `PyExcArgs`). Check `e.characters_written` and `e.errno` read back from Python.
New conformance case `tests/conformance/io_nonblocking.py` (posix-gated): exactly the "Non-blocking `_io`" lines of 3.6, in that order, plus `os.write` to a full pipe -> `BlockingIOError 11`.
Acceptance: `io_nonblocking` on Linux three lanes; `io_files`, `io_memory`, `io_posix`, `os_pipe`, `io_codecs_basic`, `encoding_defaults` on both OSes three lanes (the buffered layer is shared); W-min; pytest `tests/test_import_runtime.py`, `tests/test_smoke.py`.
Review checklist:
- Windows/nio invariance: `readOrNull`/`writeOrNeg` return exactly what `read`/`write` returned there (never null/-1), so every new branch is dead on Windows; confirm no existing branch changed meaning (`n <= 0` in `flushWrite` previously broke the loop silently on 0: it still does for 0);
- `ObjOps` untouched;
- EOF vs would-block are not conflated anywhere (`fill` 0 vs -1; an empty array vs null);
- no data loss: bytes accepted into the buffer on a would-block write are counted in `characters_written` and flushed later (the case's "flush after drain" line, and the pipe's content checked byte for byte in the case);
- no infinite loop: every loop that calls `ops.read`/`ops.write` exits on null/-1;
- `readall` on a blocking pipe still reads to EOF; on a regular file still returns everything in one call;
- `sys.stdout` over a non-blocking pipe: a would-block during `print` raises `BlockingIOError` (as CPython) rather than spinning.

### Ticket 10. `select.select` and `select.poll` on the kernel
Implementer tier: **sonnet**. New POSIX-only classes with fixed layouts and a wait loop spelled out step by step (the signal rule it reuses exists after ticket 8).
Files: new `PosixSelect.java`, new `PosixSelectModule.java`; `SelectModule.java`: in `create()`, immediately after the `if (ImportSupport.WINDOWS) { ... }` block, add `if (IoFd.KERNEL) return PosixSelectModule.create();`; make `fdOf`, `seq`, `timeoutType` package-private (no other edit; the old POSIX code below stays for nio mode).
Decision (deviation from the parent plan's wording "select rewritten on poll(2)"): `select.select` calls **`select(2)`**, as CPython does, so readiness classes, EBADF and the 1024 limit come from the kernel instead of an emulation. `select.poll` calls `poll(2)`.
`PosixSelect` (FFM): `static int select(int nfds, MemorySegment r, MemorySegment w, MemorySegment x, long micros /* -1 = NULL */)`, `static int poll(MemorySegment fds, int n, int ms)`; both return the result or `-errno`; neither dispatches signals (the module layer does).
`PosixSelectModule.create()`: module `select` with the 12 `POLL*` constants and `PIPE_BUF` (3.3), `error` = OSError, `select`, `poll` (class `select.poll`, defined as `SelectModule` defines it today: `pollNew` rejecting arguments with the same text, methods `register`, `modify`, `unregister`, `poll`).
`select(rlist, wlist, xlist, timeout=None)`:
1. Argument and timeout checks as the existing `SelectModule.select` lines 107-117 (same texts; reuse `seq`, `fdOf`, `timeoutType`).
2. For each object: `fd = fdOf(o)`; `fd >= 1024` -> `ValueError("filedescriptor out of range in select()")`.
3. Three 128-byte sets and their pristine copies in one confined Arena; `nfds = max + 1`.
4. Wait: deadline from `System.nanoTime()`; slice = 50 ms when `SignalModule.active && SignalModule.isMain()`, else the whole remaining time (`-1`/NULL for no timeout); before each call restore the sets from the copies and rebuild the timeval; result `> 0` -> done; `0` -> if the deadline passed (or it was the caller's full timeout) return three empty lists, else `SignalModule.dispatchPending()` and continue; `-EINTR` -> `dispatchPending()`, continue with the remaining time; other error -> `PosixNative.osError(errno, null)`.
5. Result: three lists of the **original objects** whose bit is set, in input order (duplicates preserved, as the existing `collect` does).
6. Timeout to microseconds: `ceil(seconds * 1e6)`.
`poll` object: `LinkedHashMap<Integer, Integer>` (registration order; re-registering keeps the original position, as a Python dict does).
- `register(fd, eventmask=POLLIN|POLLPRI|POLLOUT)`, `modify(fd, eventmask)` (unregistered -> `osError(2)` i.e. FileNotFoundError), `unregister(fd)` (unknown -> `KeyError(fd)`); mask conversion: > 65535 -> `OverflowError("Python int too large for C unsigned short")`; negative -> the lane's ValueError (3.7).
- `poll(timeout=None)`: `None` or negative -> infinite; number -> `ms = ceil(value)`; above `Integer.MAX_VALUE` -> 3.7 rule; type error -> `timeoutType(t, "an integer or None")`. Build the `pollfd` array (8 bytes each) once per call; wait loop as in `select` (slices, EINTR, deadline). Result: list of `(fd, revents & 0xffff)` for entries with non-zero `revents`, in registration order.
New conformance case `tests/conformance/select_pipes.py` (posix-gated): the select and poll lines of 3.6 on pipes (data, HUP, write end with reader closed, closed descriptor EBADF, `>= 1024` ValueError via `select.select([1024], [], [], 0)`), empty-set timeout sleeping at least the timeout, a 0.2 s `select` on an idle pipe that a thread makes readable after 0.05 s (returns early: elapsed < 0.19 printed as bool), `poll` registration order, POLLNVAL, POLLHUP, float timeout, the mask errors per lane (`sys.version_info` branches in the case), `modify`/`unregister` errors, objects with `fileno()`, and the interplay with signals: `setitimer` + handler that returns during a 0.4 s `select` (timeout not shortened), handler that raises during `poll(5000)`.
Acceptance: `select_pipes`, `select_files` (unchanged file; must pass as it does at baseline), `signal_basic` on Linux three lanes; `select_files` on Windows three lanes (unchanged); L-min, W-min; `tests/test_posix_fds.py`: under `PYJVM_POSIX_FDS=nio` the old module is still served (`select.select([], [], [], 0)` works).
Review checklist:
- `SelectModule` diff = one inserted line + three `private` removals; the insertion is *after* the Windows early return;
- fd sets are restored before every `select(2)` call (the kernel overwrites them) and the timeval rebuilt (Linux decrements it);
- the 1024 check precedes any bit write (a descriptor >= 1024 would write outside the 128 bytes: memory corruption, not an exception);
- timeouts: total wait never exceeds the request by more than one slice; a zero timeout makes exactly one call; `None` waits forever but still dispatches signals each slice on the main thread;
- EINTR path recomputes the remaining time and does not restart the full timeout;
- results return the caller's objects, not ints;
- regular files report ready for read and write (select_files lines 65-93) and the poll results for them are `POLLIN|POLLOUT` masked by the registered events;
- `pollfd.events` is written as 16 bits; `revents` read as unsigned 16 bits;
- no descriptor is created by this ticket (nothing to leak).

### Ticket 11. `select.epoll`
Implementer tier: **sonnet**. Same pattern as ticket 10 plus a Cleaner modelled on `WindowsSocketModule`; the packed struct and the close races are spelled out and tested.
Files: `PosixSelect.java`, `PosixSelectModule.java`.
`PosixSelect`: `epollCreate()` (`epoll_create1(EPOLL_CLOEXEC)`), `epollCtl(epfd, op, fd, events)` (12-byte event: `events` at 0, `data` = `fd` as u64 at 4; for `EPOLL_CTL_DEL` pass a valid dummy event, not NULL), `epollWait(epfd, MemorySegment events, int max, int ms)`; results or `-errno`.
`PosixSelectModule`: constants per 3.3/3.4 (lane rule for `EPOLLWAKEUP`); class `select.epoll`:
- State object `Ep { final AtomicInteger fd; long gen; }`, never referencing the Python object; a `java.lang.ref.Cleaner` (one static, daemon thread named `pyjvm-epoll-cleaner`, as `WindowsSocketModule.CLEANER`) registered on the Python instance closes it when collected.
- `__new__`/constructor `epoll(sizehint=-1, flags=0)`: `sizehint == -1` ok; `sizehint <= 0` otherwise (i.e. 0 or below -1) -> `ValueError("negative sizehint")`; `flags` not 0 and not `EPOLL_CLOEXEC` -> `PyException("OSError", "invalid flags")`; then `epollCreate()`, `PosixFds.adopt(fd)`, record the generation.
- `close()`: `int f = fd.getAndSet(-1); if (f >= 0) PosixFds.close(f);` (idempotent). `closed` property. `fileno()`. `fromfd(fd)` (class method): wraps the number without checking it; `adopt` is **not** called; generation = `PosixFds.gen(fd)`.
- `register(fd, eventmask=EPOLLIN|EPOLLPRI|EPOLLOUT)`, `modify(fd, eventmask)`, `unregister(fd)`: `fdOf` rules (negative -> ValueError text, non-int without `fileno` -> TypeError text), mask = low 32 bits of the Python int (no range error, no warning), `epoll_ctl` errors -> `osError(errno, null)`.
- `poll(timeout=None, maxevents=-1)`: closed check first; `maxevents == -1` -> 1023; `maxevents < 1` otherwise -> `ValueError("maxevents must be greater than 0, got " + n)`; timeout `None` or negative -> infinite, else `ms = ceil(seconds * 1000)`; type error -> `timeoutType(t, "an integer or None")`; `maxevents` not an int -> `TypeError("'str' object cannot be interpreted as an integer")` (the runtime's standard index error); event buffer `maxevents * 12` bytes in a confined Arena; wait loop as ticket 10 (50 ms slices on the main thread, EINTR, deadline). Result: list of `(fd, events)` with `fd` = low 32 bits of `data` (signed), `events` unsigned 32-bit.
- Every method except `close`, `closed`, `__exit__` raises `ValueError("I/O operation on closed epoll object")` when closed. `__enter__` returns self (closed -> the ValueError); `__exit__` closes. `repr`: default object repr with type name `select.epoll`.
- Cleaner action: `int f = fd.getAndSet(-1); if (f >= 0 && PosixFds.gen(f) == gen) close it quietly` (the 1.7 guard: someone may have closed and reused the number).
New conformance case `tests/conformance/select_epoll.py` (posix-gated): the epoll lines of 3.6; level-triggered readiness on a pipe; `EPOLLONESHOT`; `EPOLLHUP` after the writer closes; `modify`; two descriptors reported (sorted before printing); context manager; closed-object errors; `fromfd(os.dup(e.fileno()))` sharing registrations; `selectors.DefaultSelector().__class__.__name__` (`EpollSelector`) and a register/select/unregister round trip over a pipe through `selectors`; `maxevents=1` with two ready descriptors returns one; signal handler raising during `e.poll(5)`.
Acceptance: `select_epoll`, `select_pipes`, `select_files` on Linux three lanes; `select_files` Windows three lanes; L-min, W-min; `hasattr(select, "epoll")` is False on Windows (add the line to the Windows branch of `select_files.py` only if it is not already implied by line 26 there: it prints `hasattr(select, 'epoll')` already, so no change).
Review checklist:
- fd leak: the epoll descriptor is closed by `close()`, `__exit__`, or the Cleaner; the Cleaner's state holds no reference to the instance (otherwise it never runs);
- double close: `getAndSet(-1)` in both `close()` and the Cleaner; the Cleaner checks the generation; `fromfd` objects do close the number on `close()` (CPython does) but were not adopted;
- close-on-exec: `EPOLL_CLOEXEC` always (CPython forces it too);
- the event struct is packed: `data` at offset 4 written/read unaligned; stride 12; a 16-byte stride silently returns garbage for the second event (the two-descriptor line in the case catches it);
- `maxevents` bounds the native buffer; -1 -> 1023;
- check order in `poll` is: `maxevents` type, closed, timeout type/value, `maxevents` range; in `register`/`modify`/`unregister`: closed, then descriptor conversion (from reading CPython's `selectmodule.c`, **not measured**: the case prints `poll("x")` and `register("x")` on a closed object, so a wrong order shows as a case failure and is then corrected to what CPython prints);
- timeouts: seconds (float) for epoll, milliseconds for poll, seconds for select: not mixed up;
- slices and EINTR as ticket 10.

### Ticket 12. CPython's own tests, performance, triage
Implementer tier: **opus**. Open-ended diagnosis: each failure must be traced to a cause anywhere in the runtime and classified correctly; a wrong "blocked on N1" hides a real N0 bug.
No design change expected; fixes are allowed only inside the scope of tickets 3-11 (each fix is its own small commit with a conformance line added). Anything larger is listed, not fixed.
Run on Linux, 3.15 lane first, through `tools/run_cpython_test.py` (see JVM_BACKEND "Running a CPython test module"): `test_os` classes `FileTests`, `PipeTests`, `TestInvalidFD`, `FDInheritanceTests`, `BlockingTests`, `DevNullTests`, `FSEncodingTests` excluded; `test_fileio`; `test_file`; `test_select`; `test_poll`; `test_epoll`; `test_io` classes `CMiscIOTest` signal/non-blocking tests only if the runner can select them; `test_signal` `WakeupFDTests`, `WakeupSignalTests`. Many need `fork`/`subprocess` (N1) or `socket` (N3): record those as blocked, with the test names.
Output: `pyjvm-work\n0\cpython_tests.md`: per module passed/failed/errors/skipped, and for every failure one line: cause, and one of {fixed here, blocked on N1, blocked on N3, out of scope with reason}.
Performance: repeat ticket 5's cold-compile timing; plus a script that copies a 256 MiB file with `shutil.copyfileobj` and reads a 50 MB text file by lines, pyJVM nio mode against kernel mode, three runs each.
Acceptance: the report exists; no failure is left unclassified; kernel mode within +15% of nio mode on the two I/O scripts, or the numbers are reported to the planner.
Review checklist: every "fixed here" has a conformance line that fails before the fix; no fix touches shared code outside `KERNEL` guards; "blocked" classifications are plausible (the test really calls fork/subprocess/socket); timings are from the same machine state, sequential runs.

### Ticket 13. Documentation and final gates
Implementer tier: **sonnet**. Accurate docs from this plan and the tickets' results, then the full gates; no code semantics change (not haiku: the docs must state the limits correctly).
Files: `JVM_BACKEND.md` (new section "POSIX native layer": descriptors are kernel descriptors, `PYJVM_POSIX_FDS`, the FFM conventions of 1.3, the D5 rule and its limits, descriptor lifetime and the generation guard, the emulated cwd; extend "Finalizers and reference-counting timing" with the POSIX pipe-EOF consequence), `HANDOFF.md` (wave entry; remove the POSIX "placeholders" remarks for `get_inheritable`/`set_blocking`/`getuid`/umask; note that they remain placeholders on **Windows**; open item 2 done), `STDLIB_STATUS.md` (regenerate on Linux; update the host note), `pyjvm-work\native_modules_plan.md` (N0 status, pointer to this file, the `PosixFds.adopt` and chdir-action obligations for N1/N3), class comments of `IoFd`, `PosixFiles`, `SelectModule` (they still say "FileChannel"/"no pipes or sockets").
Then L-full and W-full; compare with `baseline.md`.
Acceptance: same rule as ticket 5, now including the new cases (`os_fd_flags`, `os_stat_agree`, `os_pipe`, `signal_blocking_io`, `io_nonblocking`, `select_pipes`, `select_epoll`) passing on Linux three lanes and printing the first line only on Windows.
Review checklist: docs state the limits (50 ms signal latency, uninterruptible large pipe write, late finalizer close, no `fork`, JVM-owned descriptors closable); no doc claims something the tests do not show; Windows results equal baseline by case name; comment-only changes in shared Java files do not alter code.

### Ticket 14 (deferred; owner's go-ahead after N1 has passed a full gate cycle on top of N0). Remove the switch
Implementer tier: **sonnet**. Deletions guided by greps and a per-line "no Windows caller" rule, verified by full gates on both OSes (not haiku: each deletion needs the caller check).
Also in scope: the java.nio POSIX body of `PosixModule.stat` below the ticket 4b dispatch line, once the grep shows only POSIX reached it.
Files: `IoFd.java` (`KERNEL = !ImportSupport.WINDOWS`), and delete what only nio-mode POSIX used: `IoFd.umask()`'s `/proc` parsing and the `/proc`/path heuristics in `IoFd.isatty` (only if Windows does not reach them: `isatty` is reached on Windows for non-`nativeIo` descriptors, so keep the method and delete only what is provably POSIX-only), `PosixFiles`' POSIX branches of `fstat`/`fchmod`/`unixId`/the placeholder lambdas (Windows still uses the shared registrations: check each one against `WindowsNtModule` before deleting; when in doubt leave it), the old POSIX half of `SelectModule` (everything after the `KERNEL` dispatch that Windows does not call), the POSIX `NotImplementedError` in `setWakeupFd`.
Acceptance: L-full, W-full equal to ticket 13's results.
Review checklist: for every deleted line, a grep shows no Windows caller; the Windows class-load test still passes; `PYJVM_POSIX_FDS` no longer appears in the tree except in docs' history.

## 6. Risks
1. **Engine swap under all POSIX file I/O** (parent risk 1). Mitigation: opt-in until ticket 5, full gates at the flip, `nio` rollback until ticket 14.
2. **Signals vs blocking syscalls** rest on unmeasured HotSpot facts (U3). The slicing rule does not depend on which thread takes the signal; the SIGPIPE fact is checked in ticket 1. If the JVM does not ignore SIGPIPE, the plan needs one more step.
3. **Shared `_io` code** (tickets 7, 9) is the only place Windows could change. Mitigation: new branches are unreachable there by construction (`readOrNull` never returns null), reviewed line by line, three-lane Windows runs on those tickets.
4. **Finalizer-driven closes on reused numbers** (1.7). The generation guard covers `FileIO` and epoll; N1/N3 must call `adopt`, or their descriptors are unguarded (they would behave as in nio mode, not worse).
5. **Performance**: an FFM call plus a copy per read/write, plus one `poll` per main-thread read/write once `_signal` is imported. Measured in tickets 1, 5, 12 with a +15% stop line.
6. **Lane 3.14** is unmeasured until ticket 0 (U1).
7. **JVM-owned descriptors** can now really be closed from Python (`os.closerange`, `os.close`); accepted by decision.
8. **Path stat on the import hot path** (ticket 4b): `os.stat` is now an FFM call per probe; importlib calls it thousands of times at start-up. Covered by ticket 5's cold-compile timing and ticket 12's numbers. (The earlier risk that `os.fstat` and `os.stat` disagree is removed by ticket 4b.)

## 7. 3.14 measurements and spike notes (filled in by tickets 0 and 1)
### 7.1 Ticket 0: 3.14 measurements (WSL, uv-managed CPython 3.14.10; 3.13.18; 3.15.2)
Outputs: `pyjvm-work\n0\measure-313.txt`, `-314.txt`, `-315.txt` (script `tools/posix_measure.py`, committed on `n0-fds` with ticket 0). Re-measured 2026-11-21 on a second host (WSL Ubuntu 24.04, glibc 2.39, same CPython patch levels) with the review fixes (temp dir printed as `<tmp>`, descriptor leaks closed, new `DirEntry stat nofollow` fact): `work/n0/measure-glibc239/`. The 3.14 differences below are unchanged; only descriptor numbers differ (one lower, environment-dependent), and `gen_posix_tables.py` gives the same 133 entries. Diff script: `pyjvm-work\n0\measure_diff.py`. Every difference of 3.14 against its neighbours (tmp paths normalised; nothing else differs):
- 3.14 equals **3.15** (differs from 3.13) for: `os.readinto` present (`($module, fd, buffer, /)`); `select.EPOLLWAKEUP` present (536870912); `poll.register(fd, -1)` -> `ValueError: Cannot convert negative int`; wakeup-fd failure header `Exception ignored while trying to write to the signal wakeup fd:` (the traceback body differs only by source-line context lines on 3.15). Gate these with `LangVersion.atLeast(3, 14)`.
- 3.14 equals **3.13** (differs from 3.15) for: `poll.poll(2**40)` -> `OverflowError: timeout is too large`; timeout type errors (`select.select`: `timeout must be a float or None`; `poll.poll`/`epoll.poll`: `timeout must be an integer or None`); `epoll.register(fd, 2**32)` accepted with no DeprecationWarning. Gate these with `atLeast(3, 15)`.
- All other measured items (errors, constants, signatures, struct behaviours, non-blocking `_io`, signals) are identical on all three lanes.
- `tools/gen_posix_tables.py` run under 3.13: 133 entries; 132 = "Operation not possible due to RF-kill", 133 = "Memory page has hardware error".

### 7.2 Ticket 1: spike S1 (WSL Ubuntu 24.04, glibc 2.39, kernel 6.6.87.2, Temurin 25.0.5.1; 2026-11-21)
Report: `work/n0/spike.md` (sources in `~/pyjvm-work/n0/spike/` on that host). Options exactly as 1.3: `captureCallState("errno")` on every handle, `firstVariadicArg(2)` for `open`/`fcntl`, `malloc`/`free` without capture, per-thread call state and 64 KiB buffer from `Arena.ofAuto()`; run as `java --enable-native-access=ALL-UNNAMED`.
- Items 1-4 and 6 pass: two threads x 2000 round trips without error; ENOENT/EBADF errno correct across 200000 iterations with allocation and `System.gc()` between call and read; **SIGPIPE: a write to a pipe whose read end is closed returns -1/EPIPE and the JVM survives** (main and second thread), so no `signal(SIGPIPE, SIG_IGN)` step is needed (risk 2's SIGPIPE half is closed); `poll` 50 ms timeout returns 0 after 50.1-50.8 ms, closed fd gives POLLNVAL; a single 16 MiB `read(2)` into a `malloc` buffer matches `FileChannel`.
- Item 5 (warm cache, 256 MiB in 64 KiB reads): FFM 11951 MB/s vs `FileChannel` 11337 MB/s (mean of three; ratio 1.05, a second JVM 1.07). Not slower than 2x, so ticket 12 needs no extra measurement for this.
- **New rule:** a successful call leaves the previous errno in the call-state segment (a stale 22 seen after a successful read). Read errno only after a failing return (added to checklist item 3).

## 8. Questions for the owner
All four are answered (owner, 2026-11-17); nothing is open.
- Q1. Linux runs in WSL for ticket 0 and after? **Answer: yes.** WSL pyJVM runs are allowed for N0, under the shared one-run-at-a-time lock `pyjvm-work\run.lock` (created atomically before any build/test, removed in `finally`; WSL runs take it too). See 2.2.
- Q2. Base commit and local commits? **Answer:** today's in-flight Windows work is committed first; N0 branches from that commit (B0, to be filled in by the lead in 1.2). Local branch `n0-fds` in worktree `pyjvm-work\wt-n0`, never pushed: yes.
- Q3. Move `os.stat`/`lstat` to FFM? **Answer: yes**, with anything else that must agree with `fstat`: ticket 4b.
- Q4. `select.select` on real `select(2)`? **Answer: yes** (ticket 10).
