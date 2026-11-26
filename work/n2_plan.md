# N2 plan: small libc modules on POSIX (`fcntl`, `termios`, `resource`, `pwd`, `grp`, and the `os` pty functions)

Written 2026-11-25 by a planning agent. Parent: `native_modules_plan.md` (phase N2, decision D4, "Obligations on N2 and N3 from N1"). Builds on N0 (`n0_plan.md`: kernel descriptors, `PosixNative`/`PosixFds`, FFM conventions 1.3, the D5 rule 1.6, `adopt`/`forget` 1.7) and N1 (`n1_plan.md`: `PosixProc`, `PosixOsProc`, `PosixStructSeq`, `PosixOsProc.rusage`, the wait back-off). Target: Linux x86-64 glibc only. Windows behaviour must not change and Windows must never load a `Posix*` class.

Executors: one implementer agent per ticket, of the tier named on the ticket's "Implementer tier" line (Sonnet by default; Opus where the line says so; Haiku only for fully mechanical work; never Fable). After the implementer reports, a **Haiku agent reruns the ticket's acceptance tests and the mechanical checks** (W-min/L-min/N-min, methodsize, the grep/diff checks of section 4 items 1, 2, 6, 7 and 12) and hands the logs to the reviewer. Then a fresh Opus agent reviews the ticket's diff (that ticket's review checklist plus section 4) before the next ticket starts. Every design choice is made here. If an implementer finds a fact that contradicts this plan, they stop and report; they do not pick an alternative.

## 0. How this plan was checked (and what was not)

Measured for this plan on 2026-11-25, with **CPython only and C only** (no pyJVM, no JVM), in WSL Ubuntu 24.04, glibc 2.39, kernel 6.6.87.2, x86_64, as uid 1000, without a controlling terminal, on the uv-managed interpreters `~/pyjvm-n0/.venv313` (3.13.18), `.venv314` (3.14.10), `.venv315` (3.15.2). Everything is in `E:\pyjvm-work\n2\` (ticket 0 copies it to `work/n2/`):
- struct sizes, offsets and constants of 3.2/3.3 with a C program (`layout.c` -> `layout.txt`); libc symbols and versions with `nm -D` (`run2.sh`);
- module surfaces (`m1.py` -> `m1-31x.txt`): every name, `__text_signature__` and integer constant of the five modules and the presence of the `os` terminal functions, diffed across the three lanes;
- behaviour (`m2.py` -> `m2-31x.txt`, 347-351 lines per lane): results, exception types, texts and audit events of every call quoted as "measured" in section 3, diffed across the lanes;
- the four draft conformance cases in `cases\` (`fcntl_basic`, `resource_basic`, `pwd_grp_basic`, `termios_pty`) were run twice or more under each interpreter (`runcases.sh`, in a new session, stdin `/dev/null`): stable; lane differences are exactly those of 3.8. Expected outputs are beside them (`<case>-31x-1.out`);
- a static inventory of CPython's own tests for these modules (`run3.sh`, `run4.sh`; corpus `~/cpython/Lib` at `5b28ebd`, the one N1 ticket 11 used): which tests need `fork`, `/dev/tty`, `sys.executable`, or change limits in-process (3.9). Nothing from the corpus was executed.

**Not verified** (flagged again where used):
- U1. **No Java/FFM code was run** (owner's instruction: the JVM run lock is in use and memory is tight). Every JVM-side statement is from the Java 25 documentation, the existing `Posix*` classes or JDK knowledge: NSS lookups (`getpwuid_r` loads `libnss_systemd` with `dlopen`) work inside a JVM (the JDK itself calls `getpwuid_r` at start-up); HotSpot raises the soft `RLIMIT_NOFILE` to the hard limit at start and never reads limits again; HotSpot handles `SIGXFSZ` so an over-limit write fails with `EFBIG` instead of killing the JVM; a JVM that opens a pty with `O_NOCTTY` never gets a controlling terminal. Ticket 1 probes these before runtime code depends on them.
- U2. Only glibc 2.39 was measured. All functions used exist since glibc 2.13 or earlier (3.1), so the glibc 2.34 floor that N1 set stays the requirement. Ticket 0 reruns `layout.c` on the glibc 2.35 host if that host is used for gates.
- U3. The surfaces are those of the uv-managed (python-build-standalone) reference interpreters: `fcntl` has 79 integer names and **no `F_OFD_*`**, `termios` 234, `resource` 20 (3.13, 3.14) or 22 (3.15). A distribution build differs. This plan matches the reference interpreters (same rule as N1 Q3).
- U4. The order of argument checks inside CPython's C code is from the measured consequences only; where a ticket needs an order that `m2.py` does not pin, it measures first (ticket text says where).
- U5. **Nothing was measured as root**, and nothing may be (2.3 B5): as root the "not allowed" error lines succeed and change limits.
- U6. What HotSpot does under a lowered `RLIMIT_AS`/`DATA`/`NPROC`/`CPU`/`FSIZE` is not measured and **must not be measured** (2.3 B2).
- U7. NSS sources other than `files systemd` (LDAP, sssd, NIS) and account-database entries that are not valid UTF-8 were not measured: this host has none. The surrogateescape decoding of 1.6 is from CPython's source (`PyUnicode_DecodeFSDefault`).
- U8. `tcdrain`, `tcsendbreak` and `tcsetattr(TCSADRAIN)` were measured only with no pending output (they return at once). Their blocking behaviour is not.
- U9. The draft cases have not run under pyJVM. A line may expose a runtime gap outside N2 (`array.array` as an ioctl buffer, `warnings.catch_warnings`, `select` on a pty). The implementer then reports; a case line is dropped only with the reviewer's agreement and a recorded reason (N1 practice).
- U10. The N2 baseline is N1's final gate result (N1 ticket 12, run at `25d8e3b` plus the documentation-only diff of `7c9010f`). Ticket 0 records it by case name.
- U11. The size (about 1,500 new Java lines plus three generated constant tables, against the parent plan's 1,100) is an estimate.

**Incident while measuring (recorded so that nobody repeats it):** the first draft of `termios_pty.py` wrote a line to the pty master with echo on, called `tcflush(slave, TCIFLUSH)` at once, then did a blocking read of the echo on the master. A pty moves data between its ends asynchronously (a kernel worker); when the flush ran before the worker, the input was discarded before any echo existed and the read blocked forever. One run in three hung until its `timeout 120`. Nothing else was affected. Rules 2.3 A4/A5 exist because of this; the committed draft waits with `select` and reads no echo after a flush (18 further runs, no hang).

## 1. Design

### 1.1 Architecture
- Everything N2 adds exists only when `IoFd.KERNEL` (kernel descriptors), like N1. New POSIX-only classes, never initialised on Windows:
  - `PosixLibcConsts`: generated integer tables of `fcntl`, `termios`, `resource` (names sorted, values parallel); data only, no FFM import.
  - `PosixArgs`: argument converters shared by the new modules (descriptor argument, buffer argument, lane texts); no FFM.
  - `PosixFcntlModule`: `fcntl` (`fcntl`, `ioctl`, `flock`, `lockf`).
  - `PosixResourceModule`: `resource`.
  - `PosixAccounts`: `pwd` and `grp` (one class: the lookups share the buffer-growth loop, the string decoding and the enumeration lock).
  - `PosixOsTty`: the `os` functions `openpty`, `ttyname`, `posix_openpt`, `grantpt`, `unlockpt`, `ptsname`.
  - `PosixTermiosModule`: `termios`.
- `PosixStructSeq` (N1) is the factory for `pwd.struct_passwd` and `grp.struct_group`; `resource.struct_rusage` stays the one class `PosixOsProc.rusageClass()` creates, and `resource.getrusage` builds its result with `PosixOsProc.rusage(MemorySegment)` (N1 obligation 1). `PosixStructSeq` gains construction from a sequence and `__reduce__` (ticket 1).
- Shared files get the smallest possible edit: `ImportSupport` (`POSIX_ONLY` gains `"termios"`; four `case` arms of `createNative` become `IoFd.KERNEL ? PosixXModule.create() : StubModules.x()`, one arm added for `termios`), `PosixOsProc.install` (last line `PosixOsTty.install(m);`), `StubModules` (fake values removed, 1.9), `tests/test_import_runtime.py` (class list), `tests/conformance/os_platform.py` (Windows branch, 1.8).
- No new `ProcessBuilder`, no `System.getenv`, no path handed to the kernel that is relative (the only paths are `ptsname` results, which are absolute, so the emulated cwd of N0 1.1 is not involved).
- Out of N2, stay absent (ticket 9 lists them in JVM_BACKEND): `os.login_tty`, `os.forkpty` (need `fork` or change the JVM's own session), `os.tcgetpgrp`, `os.tcsetpgrp`, `os.ctermid`, `os.getlogin`, `os.getgroups`, `os.getgrouplist`, `os.setsid`; `pty.fork` and `pty.spawn` therefore fail with AttributeError as before; `fcntl` operations that hand the kernel a struct larger than the buffer rule of 1.4 allows are the caller's problem, as in CPython.

### 1.2 FFM conventions
N0 1.3 and N1 1.2 apply unchanged (handles through `PosixNative.fn`, errno capture, thread-local call state, no `Linker.Option.critical`, explicit offsets, `PosixNative.rethrow`). Additions:
- **Functions that return the error number** instead of setting errno: `getpwuid_r`, `getpwnam_r`, `getgrgid_r`, `getgrnam_r`, `ttyname_r`, `ptsname_r`. Build them with `PosixNative.fnNoCapture` and use the return value.
- `getpwent`, `getgrent` return a pointer to static storage or NULL; `setpwent`/`endpwent`/`setgrent`/`endgrent` return void (`FunctionDescriptor.ofVoid()`), no capture.
- Variadic: `fcntl(int, int, ...)` and `ioctl(int, unsigned long, ...)` with `Linker.Option.firstVariadicArg(2)`. `PosixFcntlModule` declares its own handles, two per function: third argument `JAVA_LONG` (integer form; an `int` argument is passed sign-extended, which is what the x86-64 ABI does for a C `int` vararg) and `ADDRESS` (buffer form). `PosixFds`'s private `fcntl` handles are not widened.
- A struct returned through a pointer (`struct passwd *`, `struct group *`) is read with `MemorySegment.reinterpret(size)`; each `char *` in it with `reinterpret(Long.MAX_VALUE)` and a scan for the NUL. **Every string is copied to the Java heap before the next libc call and, for the `ent` functions, inside the enumeration lock** (the storage is the caller's buffer or libc's static one).
- Native memory for struct and buffer arguments comes from a confined `Arena` per call (these calls are not hot: no thread-local reuse; exception: `F_GETFD`/`F_GETFL`-style integer calls allocate nothing).

### 1.3 Errors and argument conversion
- `PosixNative.osError(errno, null)` as in N0/N1; none of these functions has a filename.
- `termios.error` is **not** an `OSError`: it is a class `termios.error` with base `Exception` (measured MRO `error, Exception, BaseException, object`), raised with `args == (errno, strerror)`; it has no `errno` attribute. Create it once per module (`PyRuntime.classCreate` + `classAddBase`, the pattern `StubModules.socket` uses for `gaierror`).
- `resource.error is OSError`.
- **Descriptor argument** (`fcntl.*`, `termios.*`; CPython's `PyObject_AsFileDescriptor`): `PosixArgs.fd(Object)`: an int (bool allowed), else the result of `fileno()`; no `fileno` -> `TypeError: argument must be an int, or have a fileno() method.`; a `fileno()` result that is not an int -> the lane's text (3.8); a value that does not fit a C int -> `OverflowError: Python int too large to convert to C int`; negative -> `ValueError: file descriptor cannot be a negative integer (-1)`. `SelectModule.fdOf` (shared file, same texts for 3.13/3.14) is not edited. The `os` functions of ticket 6 take a plain C int instead (`PosixOsProc.cInt`: `os.ttyname("x")` -> `'str' object cannot be interpreted as an integer`, and a closed number reaches the kernel).
- Integer arguments: C int through `PosixOsProc.cInt` (already package-private). The **third argument of `fcntl.fcntl`/`fcntl.ioctl` and the `ioctl` request are not range-checked**: any Python int is accepted and truncated to 32 bits (measured: `2**31`, `2**32`, `-1`, `2**64` all work); 3.15 additionally warns (3.8).
- Lane-dependent texts come from `LangVersion.atLeast` and are listed in 3.8. A `DeprecationWarning` is raised through the Python `warnings` module (the technique at `PosixOsProc.java:260-266`), so `warnings.catch_warnings` sees it.

### 1.4 `fcntl`
- `fcntl(fd, cmd, arg=0)`:
  1. `fd` per 1.3; `cmd` C int; audit event `fcntl.fcntl` with `(fd, cmd, arg)`, **`arg` being `None` when the caller gave two arguments** (measured).
  2. `arg` an int: integer handle; result is the int the call returned.
  3. `arg` a buffer (bytes; `str`, encoded UTF-8; on 3.14+ any bytes-like object, 3.8): copy into a native buffer of `max(len, 1024)` bytes plus 8 guard bytes, zero-filled; pointer handle; return `bytes` of the original length read back from the buffer. Length limits per lane in 3.8. The kernel never sees heap memory.
  4. Failure -> `OSError` by errno. `EINTR` -> `SignalModule.dispatchPending()` and retry (PEP 475, as CPython).
  5. **`F_DUPFD` (0) and `F_DUPFD_CLOEXEC` (1030) with an integer argument create a descriptor: on success call `PosixFds.adopt(result)`** (N0 1.7, N1 obligation 3). `F_DUPFD`'s result is inheritable because Python asked for it.
  6. `F_SETLKW` (7) and `F_OFD_SETLKW` (38) go through the lock-wait rule of 1.5.
- `flock(fd, operation)`: audit `fcntl.flock` `(fd, operation)`; libc `flock`; without `LOCK_NB` through 1.5.
- `lockf(fd, cmd, len=0, start=0, whence=0)`: audit `fcntl.lockf` `(fd, cmd, len, start, whence)` with **`len` and `start` as `None` when omitted and `whence` as given or 0** (measured `(6, None, None, 0)`); `cmd & ~LOCK_NB`: `LOCK_UN` -> `F_UNLCK`, `LOCK_SH` -> `F_RDLCK`, `LOCK_EX` -> `F_WRLCK`, anything else -> `ValueError: unrecognized lockf argument`; `len`, `start` as C `long` (`OverflowError: Python int too large to convert to C long`), `whence` C int; fill `struct flock` (3.2) and call `fcntl(fd, LOCK_NB set ? F_SETLK : F_SETLKW, &l)`. libc `lockf(3)` is not used (CPython does not use it either).
- `ioctl(fd, request, arg=0, mutate_flag=True)`: audit `fcntl.ioctl` `(fd, request, arg)`; `request` truncated to 32 bits and passed zero-extended as `unsigned long`;
  - int `arg`: integer handle, returns the int;
  - a **writable** buffer (bytearray, `array.array`, writable memoryview) with `mutate_flag` true: native buffer `max(len, 1024)` + guard, copy in, call, **copy the first `len` bytes back into the object** (write-back as `PosixOsFds.readinto` does), return the int result; any length is allowed (measured with 2000 bytes);
  - a read-only buffer or `str`, or a writable one with `mutate_flag` false: as `fcntl`'s buffer form, returns `bytes`; length limit per lane (3.8).
  - `EINTR` retry as above. A blocked `ioctl` on the main thread is not sliced (limit, 1.5).
- Constants: the 79 names of `m1-31x.txt` from `PosixLibcConsts` (identical on the three lanes).
- Known consequences, documented in ticket 9, no guard (CPython semantics): Python can change flags of JVM-owned descriptors by number; **POSIX record locks (`lockf`, `F_SETLK`) belong to the process and are dropped when any descriptor of that file is closed in the process**, which under pyJVM includes opens and closes the JDK makes on its own (java.nio path operations, the importer reading a source file). `flock` and OFD locks are per open file description and unaffected. A descriptor that an `ioctl` creates (rare) is not adopted.

### 1.5 Blocking calls and signals (D5 applied to N2)
Facts as N0 1.6: Python handlers run only on the main thread at `SignalModule.dispatchPending()`; a libc call blocked on the main thread is generally not interrupted by a signal.
- **Lock waits** (`flock` without `LOCK_NB`; `F_SETLKW`/`F_OFD_SETLKW` through `fcntl.fcntl` or `lockf`): one function `PosixFcntlModule.lockWait(call)`, `call(boolean wait)` returning 0 or `-errno`:
  ```
  if !(SignalModule.active && SignalModule.isMain()):
      loop: r = call(true); if r == -EINTR { SignalModule.dispatchPending(); continue }; return r
  delay = 1 ms
  loop:
      r = call(false)             // flock: operation | LOCK_NB; fcntl: F_SETLK for F_SETLKW, 37 for 38
      if r != -EAGAIN && r != -EACCES: return r        // acquired, or a real error
      SignalModule.dispatchPending()
      SignalModule.sleep(delay); delay = min(delay * 2, 50 ms)
  ```
  The back-off is the one `PosixProc.await` uses for "any child" waits (including its KeyboardInterrupt check when the main thread's interrupt flag is set): share that code, do not copy it. Consequences, documented (ticket 9): on the main thread a waiting lock is polled, so a handler runs at most 50 ms late and a handler that raises propagates without the lock being taken; the kernel's FIFO queue of waiters and its `EDEADLK` deadlock detection do not apply to a polled wait; acquisition can be up to 50 ms later than the release. Off the main thread, or before `_signal` is imported, it is one blocking call. (Owner question Q3.)
- **Other calls that can block** (`termios.tcdrain`, `tcsendbreak`, `tcsetattr` with `TCSADRAIN`/`TCSAFLUSH`, `tcflow`, any `ioctl`): one call, `EINTR` -> `dispatchPending()` and retry. Limit, documented: a handler does not run while the main thread is blocked inside one of them.
- `dispatchPending()` is never called between a libc call and the copy-out of its result (N0 1.6 item 3).

### 1.6 `pwd` and `grp`
- Lookups: `getpwuid_r`, `getpwnam_r`, `getgrgid_r`, `getgrnam_r` with a native buffer starting at 1024 bytes (`sysconf(_SC_GETPW_R_SIZE_MAX)` = name 70, `_SC_GETGR_R_SIZE_MAX` = 69; both 1024 here; use `PosixProc.sysconf`, fall back to 1024 on -1), **doubled while the function returns `ERANGE` (34)**, up to 64 MiB, then `MemoryError`. Result pointer NULL (with any other return value) -> the KeyError of 3.6. No errno is read.
- Enumeration (`getpwall`, `getgrall`): `setpwent(); while ((p = getpwent()) != NULL) copy; endpwent();` under one static Java lock (`PosixAccounts.ENT_LOCK`, shared by both databases), `endpwent` in `finally`. This is what CPython does (the non-reentrant function under a mutex); the JDK does not call the `ent` functions. Returns a `list`.
- Why libc and not a Java parser of `/etc/passwd`: NSS sources (this host: `files systemd`) would be missed (parent plan).
- Strings (`pw_name`, `pw_passwd`, `pw_gecos`, `pw_dir`, `pw_shell`, `gr_name`, `gr_passwd`, each `gr_mem` item) are decoded as UTF-8 with surrogateescape (the decoder `PosixProc.confstr` uses). glibc does not return NULL for these fields; if a `char *` is NULL anyway, the field is `""` (never a crash; recorded as untested, U7). `gr_mem` is a `list` of str from the NULL-terminated `char **`.
- ids: `pw_uid`, `pw_gid`, `gr_gid` are unsigned 32-bit, as Python ints, except that the value `2**32 - 1` is returned as `-1` (CPython's `_PyLong_FromUid`).
- Argument rules are in 3.6 (the uid/gid converter is the subtle part: which values give `KeyError` with the number, `KeyError` without it, or `OverflowError` differs between `pwd` and `grp`; all measured).
- Name arguments: `str` only; an embedded NUL -> `ValueError: embedded null byte`; encoded UTF-8 with surrogateescape (measured: `'\udcff'` gives the KeyError, not an encoding error).
- No audit events (measured).

### 1.7 `resource`
- `getrlimit(resource)`: C int; outside `[0, 16)` -> `ValueError: invalid resource specified`; libc `getrlimit` into a 16-byte `struct rlimit`; a 2-tuple of ints, each value converted by the lane's rule for infinity (3.8).
- `setrlimit(resource, limits)`: resource check first; then audit `resource.setrlimit` `(resource, limits)` (measured: the event is raised for a valid resource even when `limits` is then rejected, and not for an invalid resource); then `limits`: a non-iterable -> `TypeError: 'int' object is not iterable`, a sequence whose length is not 2 -> `ValueError: expected a tuple of 2 integers` (a list is accepted); item conversion per 3.6/3.8; libc `setrlimit`; `EINVAL` -> `ValueError: current limit exceeds maximum limit`, `EPERM` -> `ValueError: not allowed to raise maximum limit`, others -> `OSError`.
- `prlimit(pid, resource, limits=None)`: pid C int, resource check, audit `resource.prlimit` `(pid, resource, limits)`; libc `prlimit(pid, resource, new or NULL, &old)`; returns the **old** limits; `EINVAL` -> `ValueError: current limit exceeds maximum limit`; **`EPERM` -> `PermissionError`** (unlike `setrlimit`; measured for pid 1 and for raising a child's hard limit); `ESRCH` -> `ProcessLookupError`.
- `getrusage(who)`: C int; libc `getrusage` into a zeroed 144-byte buffer; `EINVAL` -> `ValueError: invalid who parameter`; result `PosixOsProc.rusage(buffer)`.
- `getpagesize()`: `PosixProc.sysconf(30)`.
- Constants: the `RLIMIT_*` and `RUSAGE_*` names from `PosixLibcConsts`; `RLIM_INFINITY` (and on 3.15 `RLIM_SAVED_CUR`, `RLIM_SAVED_MAX`) per lane in code (3.8). `error` = `OSError`. `struct_rusage` = `PosixOsProc.rusageClass()`.
- No guard in the runtime (CPython semantics). Facts for the documentation (ticket 9): the limits are those of the JVM process; HotSpot has already raised the soft `RLIMIT_NOFILE` to the hard limit (N1 gap 1.6 b), so `getrlimit(RLIMIT_NOFILE)` reports `soft == hard` where CPython reports the launcher's soft limit; lowering `RLIMIT_AS`, `DATA`, `NPROC`, `CPU`, `FSIZE` or `NOFILE` inside a JVM can stop the JVM itself (allocation, thread creation, class loading), which is why 2.3 B forbids it in tests.

### 1.8 Terminals: the `os` pty functions and `termios`
- `os.openpty()` (no arguments: `posix.openpty() takes no arguments (1 given)`): **not** libc `openpty`. Steps: `posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC)`; `grantpt`; `unlockpt`; `ptsname_r` (64-byte buffer, doubled on `ERANGE`); `open(name, O_RDWR | O_NOCTTY | O_CLOEXEC)` through the existing `PosixFds` open path. Both descriptors are `adopt`ed; a failure at any step closes what was opened and raises `OSError`. Returns `(master, slave)`, both non-inheritable and blocking (measured for CPython). Reasons: both ends are close-on-exec atomically, and `O_NOCTTY` is explicit, so **a JVM that is a session leader can never acquire the pty as its controlling terminal** (which would later deliver SIGHUP to the JVM when the master closes).
- `os.ttyname(fd)`: `ttyname_r`, error number -> `OSError` (pipe 25, closed 9); a master gives `/dev/ptmx`. `os.posix_openpt(oflag)`: `posix_openpt(oflag | O_CLOEXEC)`, adopted (measured: CPython's result is non-inheritable). `os.grantpt(fd)`, `os.unlockpt(fd)`: errno. `os.ptsname(fd)`: `ptsname_r`. All four exist on the three reference lanes (measured).
- `termios` functions (all positional-only, `fd` per 1.3; every failure is `termios.error(errno, strerror)`):
  - `tcgetattr(fd)`: libc `tcgetattr` into a zeroed 60-byte `struct termios` (3.2); result `[iflag, oflag, cflag, lflag, ispeed, ospeed, cc]`: four flags as unsigned ints, speeds from `cfgetispeed`/`cfgetospeed`, `cc` a list of 32 items, each `bytes` of length 1, **except that when `ICANON` is off in `lflag`, `cc[VMIN]` and `cc[VTIME]` are ints** (measured).
  - `tcsetattr(fd, when, attributes)`: `when` C int; `attributes` must be a `list` of 7 (`TypeError: tcsetattr, arg 3: must be 7 element list`, also for a tuple); `tcgetattr(fd)` first to fill the struct (its failure is the error for a pipe: 25); flags as C long truncated to 32 bits (measured: `2**40` and `-1` are accepted; a str -> the integer TypeError); `cc` must be a `list` of 32 (`TypeError: tcsetattr: attributes[6] must be 32 element list`); each item `bytes` of length 1 or an int truncated to a byte (measured: 300 -> `b','`), else the lane's TypeError (3.8); `cfsetispeed`, `cfsetospeed` (an unknown speed -> `termios.error(22, ...)`), then libc `tcsetattr` (an unknown `when` -> error 22).
  - `tcsendbreak(fd, duration)`, `tcdrain(fd)`, `tcflush(fd, queue)`, `tcflow(fd, action)`: C int arguments, the libc functions.
  - `tcgetwinsize(fd)`: `ioctl(fd, TIOCGWINSZ)` -> `(rows, cols)`. `tcsetwinsize(fd, winsize)`: a sequence of exactly two (`TypeError: tcsetwinsize, arg 2: must be a two-item sequence`), items as C long (`2**70` -> the C long OverflowError), each in `[0, 65535]` else `OverflowError: winsize value(s) out of range.`; read the current `struct winsize`, replace rows and columns (pixel fields kept), `ioctl(fd, TIOCSWINSZ)`.
  - Constants: the 234 names of `m1-31x.txt` (identical on the three lanes), from `PosixLibcConsts`.
- `termios` becomes a `POSIX_ONLY` native module; `tty`, `pty` (its `openpty`), `getpass` and `_pyrepl.fancy_termios` import (`_pyrepl.unix_console` still needs `_curses`).
- No guard against calls on the JVM's own terminal (CPython semantics): `tcsetattr` on descriptor 0 changes the user's terminal exactly as in CPython, and from a background process group it stops the JVM with SIGTTOU as it would stop CPython. Tests never do this (2.3 A).

### 1.9 Windows and the `nio` rollback mode
- **Windows:** `import fcntl`, `termios`, `resource`, `pwd`, `grp` raise `ModuleNotFoundError: No module named '<name>'` (unchanged for the four that are in `POSIX_ONLY` today; `termios` is not found today through the path search and afterwards through `POSIX_ONLY`: same exception type and message, checked by `os_platform`). `os.openpty` and the other five functions do not exist in `nt`. No `Posix*` class is initialised: `tests/test_import_runtime.py::test_windows_never_loads_posix_ffm` lists the seven new classes. `tests/conformance/os_platform.py` gains, in its `if os.name == "nt":` branch, one line that tries to import the five names and prints the exception type names, plus `hasattr(os, "openpty")`.
- **`PYJVM_POSIX_FDS=nio`** (still present; N0 ticket 14 is released but not done; owner question Q2): `fcntl`, `pwd`, `grp`, `resource` stay the `StubModules` shells, `termios` raises ModuleNotFoundError (`createNative`: `case "termios" -> { if (!IoFd.KERNEL) throw notFound(name); yield PosixTermiosModule.create(); }` or the equivalent in that file's style), the `os` pty functions are absent. **The shells stop returning made-up values** (the no-placeholders rule; HANDOFF side finding of N1 ticket 11): `resource.getrlimit`, `setrlimit`, `getpagesize`, `pwd.getpwall`, `grp.getgrall` join `unsupported(...)` and raise `NotImplementedError`. If ticket 14 lands before an N2 ticket, that ticket deletes the shell instead and the `IoFd.KERNEL ?` arm collapses.

### 1.10 Descriptor lifetime
N0 1.7 and N1 1.8 hold. New descriptor sources and their rule: `os.openpty` (two), `os.posix_openpt`, `fcntl.fcntl(F_DUPFD / F_DUPFD_CLOEXEC)`: created close-on-exec except plain `F_DUPFD`, `adopt`ed after success, closed by Python through `PosixFds.close`. A pty pair is two ordinary descriptors for `_io`, `select`, `os.read`/`os.write` and `subprocess` (a child can be given the slave as stdin/stdout through the existing `DUP2` actions).

## 2. Environment and workflow

### 2.1 Trees
- Repo `E:\pyjvm`; scratch and run lock in `E:\pyjvm-work`; WSL clone `~/pyjvm-n0` with a remote `win` (N1 2.1). Branch **`n2`**, created from **B2 = `7c9010f`** (tip of `n1-proc`: N1 complete, local only; N0 is origin `main` `68a5099`). One local commit per ticket, never pushed unless the owner asks.
- Scratch for this phase: `E:\pyjvm-work\n2\` (Windows) and `~/pyjvm-work/n2/` (WSL). The evidence of section 0 is in the former.

### 2.2 Rules for every ticket
N0 2.2 and N1 2.2 apply word for word (the run lock `E:\pyjvm-work\run.lock` for every build/test/gate on either OS, one JVM run at a time, uv-managed Pythons and never bare `python` on Windows, WSL commands as script files written with the Write tool, reviewer gets ticket text + diff + logs, "not run" said explicitly). Gates run with SIGHUP and SIGQUIT reset to default (N1 ticket 6 lesson) and, new for N2, **in their own session with stdin from `/dev/null`** (2.3 A6). The sets:
- **W-min (Windows):** runtime compiles (`javac --release 25`), `python -m pyjvm315.methodsize --runtime` reports 0 methods over 8000 bytes, `pytest tests/test_import_runtime.py -q`, and conformance cases `os_pipe, os_platform, io_files, select_files, signal_basic, subprocess_windows, os_putenv_child, native_stdlib_modules` on the 3.15 lane, plus every case the ticket adds (each prints only `nt` on Windows).
- **L-min (WSL, three lanes):** `io_files, io_posix, io_memory, os_fchmod, os_putenv, os_platform, select_files, signal_basic, encoding_defaults`.
- **N-min (WSL, three lanes):** the N2 cases that exist at that ticket, of `resource_basic, pwd_grp_basic, fcntl_basic, termios_pty`, plus `os_fd_flags, os_pipe, os_terminal_size, os_wait_rusage, os_sysconf, subprocess_posix, select_pipes, signal_blocking_io`; and `pytest tests/test_posix_fds.py tests/test_posix_process.py tests/test_posix_libc.py -q` (the last file is new in ticket 1).
- **W-full / L-full** (ticket 9 only): pytest, methodsize, full conformance on three lanes; L-full also the strict lane; compared by case name with ticket 0's baseline.
- The per-ticket implementer brief is `E:\pyjvm-work\n2\brief_common.md` (text in 2.4); the orchestrator sends it with the ticket text.

### 2.3 SAFETY rules (tests, conformance cases, scratch scripts, CPython-test runs)
"The runner" below means any process that is not a throw-away child the script itself started: the JVM under test, pytest, the conformance harness, the agent's shell.

**A. Terminals**
1. Never call `tcsetattr`, `tcsetwinsize`, `tcflush`, `tcflow`, `tcsendbreak`, `tcdrain`, a `TIOC*`/`TC*` `ioctl`, `tty.setraw`, `tty.setcbreak` or `tty.cfmakeraw`+set on descriptor 0, 1 or 2, on `/dev/tty`, `/dev/console`, or on any terminal the script did not create itself with `os.openpty()` / `os.posix_openpt()`. `tcgetattr` on those is forbidden too (one rule, no exceptions to argue about).
2. Never open a terminal device without `O_NOCTTY`. No `os.login_tty`, no `TIOCSCTTY`, no `os.setsid` in a runner.
3. Not in any case or test: `getpass.getpass`, `pty.spawn`, `pty.fork`, `input()` on a terminal. `getpass.getuser()` is fine (no terminal).
4. A pty transfers data asynchronously. Read only data that will certainly arrive; before a flush, wait with `select` until the data is there; never read an echo after a flush (the incident in section 0).
5. Every script that touches a pty runs under `timeout` (the conformance runner has no per-case timeout: each ticket runs `termios_pty` alone under `timeout 300` before putting it into a set).
6. Gates, measurement scripts and CPython-test runs start in a new session with stdin from `/dev/null` (`setsid ... < /dev/null`), so the process has no controlling terminal: CPython's `IoctlTestsTty` and the `/dev/tty` paths of `getpass` then skip or fall back.

**B. Resource limits**
1. Never lower a **hard** limit of a runner (a non-root process cannot raise it again).
2. Soft limits of a runner: the only permitted changes are `RLIMIT_NOFILE` lowered by **one** and restored in a `finally`, and `RLIMIT_CORE`. Never lower, even briefly, `RLIMIT_AS`, `DATA`, `STACK`, `NPROC`, `CPU`, `FSIZE`, `MEMLOCK`, `RSS` or `NOFILE` (by more than one) in a process that contains a JVM.
3. Real lowering is tested with `resource.prlimit` on a `sleep 30` child the script started (killed by its pid and reaped, N1 2.3), or inside `sh -c 'ulimit ...'`.
4. `prlimit` only on pid 0, the script's own pid, its own child, or a pid that does not exist. (`m2.py` also read pid 1's limits to measure `EPERM`; do not repeat it in cases.)
5. Scripts skip their "not allowed" lines when `os.getuid() == 0` and never run as root on purpose.
6. CPython tests that change limits in-process (3.9) are run only in ticket 8, one test per fresh JVM, under `timeout`, with output through a pipe (not a file: `RLIMIT_FSIZE`), after a warm-up run has filled the compile caches. **`test_subprocess.POSIXProcessTestCase.test_preexec_fork_failure` (soft `RLIMIT_NPROC` 0) is never run under pyJVM.**

**C. Signals and processes:** N1 2.3 applies unchanged (never signal pid <= 1, pid 0, a negative pid, pgid 0/1 or the runner's group; only children the script created, by pid; sleeping children are `sleep 30` at most and reaped; every `subprocess` call has `timeout=`).

**D. Locks and files:** lock only temporary files the script created; a test that waits for a lock has a holder that releases within seconds, runs the waiter in a thread with a join timeout, or uses `LOCK_NB`. `pwd`/`grp` tests are read-only: nothing writes under `/etc`, no `useradd`.

**E.** A reviewer rejects a diff that violates A1-A3, B1-B5 or C without running it.

### 2.4 Common brief for every N2 implementer (`E:\pyjvm-work\n2\brief_common.md`; ticket 0 writes this file with this text)
- Spec: `work/n2_plan.md`: read sections 1 (design), 2 (workflow, **2.3 SAFETY**), 3 (reference data), 4 (review checklist) and your ticket in section 5. Conventions: `work/n0_plan.md` 1.3 (FFM), 1.6, 1.7; `work/n1_plan.md` 1.2, 1.3, 1.5. Latest state: the end of `HANDOFF.md` (append-only log; append one dated entry for your ticket, never rewrite earlier text).
- Owner decisions for N2: (filled in by the orchestrator from the answers to section 8).
- Do NOT commit or push. Leave the working tree with your diff; apply the same diff to the WSL clone `~/pyjvm-n0` (branch `n2`): in `E:\pyjvm` `git add -N <new files>; git diff --output=E:/pyjvm-work/n2/tN.patch; git reset`; in WSL `cd ~/pyjvm-n0 && git checkout -q -- . && rm -f <new files> && git apply /mnt/e/pyjvm-work/n2/tN.patch`.
- Compile-check before any JVM run: `javac --release 25 -d <scratch dir outside the repo> runtime/pyjvm315/parser/*.java runtime/pyjvm315/runtime/*.java`.
- ONE JVM run at a time, under the run lock `E:\pyjvm-work\run.lock` (WSL `/mnt/e/pyjvm-work/run.lock`); helpers `work/n0/run_lock.sh` and `work/n0/RunLock.ps1`. Memory is tight. CPython-only measurements need no lock.
- Linux conformance: `PYJVM_TREE=~/pyjvm-n0 [LANES=315] bash /mnt/e/pyjvm/work/n0/conf_cases.sh case1 case2 ...`, started as `setsid perl -e '$SIG{HUP}=$SIG{QUIT}="DEFAULT"; exec @ARGV' bash ... < /dev/null`. Windows: `work/n0/run_cases_n0.ps1 -CaseList 'a,b' -Lanes '315' -Log f` with `$env:PYJVM_WORK='E:\pyjvm-work'; $env:PYJVM_MAIN='E:\pyjvm'`. Methodsize: `python -m pyjvm315.methodsize --runtime` (0 methods over 8000 bytes). Working examples: `E:\pyjvm-work\n1\t6\rerun.sh`, `E:\pyjvm-work\n0\t12fix_lin.sh`, `t12fix_win.ps1`.
- From Git Bash call WSL as `MSYS_NO_PATHCONV=1 wsl -e bash /mnt/e/...script.sh` (WSL commands as script files, written with the Write tool; no heredocs) and filter lines containing "bogus". Long runs: detach and poll logs sparingly.
- Python only through uv (`uv run --no-project ...`) or the WSL venvs (`~/pyjvm-n0/.venv313/314/315`); never bare python/python3 on Windows.
- SAFETY (critical): never touch the settings of a terminal you did not create with `os.openpty()` (not fd 0/1/2, not `/dev/tty`); every pty script under `timeout`, in its own session, stdin `/dev/null`; never lower a hard limit; in a JVM process only the soft `RLIMIT_NOFILE` minus one (restored) and `RLIMIT_CORE` may change; lower limits for real only with `prlimit` on your own `sleep 30` child; never as root; never signal pid <= 1, pid 0, negative pids, pgid 0/1 or your own group (a past `os.killpg(1, 15)` killed every WSL process); do not shut down WSL.
- Windows must never load `Posix*` classes (`tests/test_import_runtime.py::test_windows_never_loads_posix_ffm`). No placeholders. Do not commit compiled jars/classes.
- Report under 400 words: files changed, what each acceptance item showed (pass counts per lane/OS), any deviation from the plan and why, anything not done or not verified.

## 3. Reference data (Linux x86-64, glibc 2.39; measured unless marked)

### 3.1 libc functions (all in `libc.so.6`, symbol version `GLIBC_2.2.5` unless noted)
| C prototype | FunctionDescriptor | Notes |
| --- | --- | --- |
| `int fcntl(int, int, ...)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG)` and `(..., ADDRESS)` | `firstVariadicArg(2)`; errno |
| `int ioctl(int, unsigned long, ...)` | `of(JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG)` and `(..., ADDRESS)` | `firstVariadicArg(2)`; errno |
| `int flock(int, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT)` | errno; conflict with `LOCK_NB` is `EAGAIN` (11) |
| `int getrlimit(int, struct rlimit*)`, `setrlimit` | `of(JAVA_INT, JAVA_INT, ADDRESS)` | errno |
| `int prlimit(pid_t, int, const struct rlimit*, struct rlimit*)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)` | `GLIBC_2.13`; NULL new = read only |
| `int getrusage(int, struct rusage*)` | `of(JAVA_INT, JAVA_INT, ADDRESS)` | errno |
| `int getpwuid_r(uid_t, struct passwd*, char*, size_t, struct passwd**)`, `getgrgid_r` | `of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)` | no capture; **returns the error**; `ERANGE` 34 = buffer too small; result cell NULL = not found |
| `int getpwnam_r(const char*, ...)`, `getgrnam_r` | `of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)` | as above |
| `void setpwent(void)`, `endpwent`, `setgrent`, `endgrent` | `ofVoid()` | no capture |
| `struct passwd *getpwent(void)`, `struct group *getgrent(void)` | `of(ADDRESS)` | no capture; static storage; under `ENT_LOCK` |
| `int tcgetattr(int, struct termios*)` | `of(JAVA_INT, JAVA_INT, ADDRESS)` | errno; a pipe gives `ENOTTY` 25 |
| `int tcsetattr(int, int, const struct termios*)` | `of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)` | errno |
| `speed_t cfgetispeed(const struct termios*)`, `cfgetospeed` | `of(JAVA_INT, ADDRESS)` | no capture |
| `int cfsetispeed(struct termios*, speed_t)`, `cfsetospeed` | `of(JAVA_INT, ADDRESS, JAVA_INT)` | errno (`EINVAL` for an unknown speed) |
| `int tcsendbreak(int, int)`, `tcflush(int, int)`, `tcflow(int, int)` | `of(JAVA_INT, JAVA_INT, JAVA_INT)` | errno |
| `int tcdrain(int)` | `of(JAVA_INT, JAVA_INT)` | errno; can block |
| `int posix_openpt(int)` | `of(JAVA_INT, JAVA_INT)` | errno |
| `int grantpt(int)`, `unlockpt(int)` | `of(JAVA_INT, JAVA_INT)` | errno (`grantpt` on a pipe: `EINVAL`) |
| `int ptsname_r(int, char*, size_t)`, `ttyname_r` | `of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG)` | no capture; returns the error (`ENOTTY` 25, `EBADF` 9, `ERANGE` 34) |
`openpty` exists (`GLIBC_2.34`) and is deliberately not used (1.8).

### 3.2 Struct layouts (`layout.txt`)
- `struct flock`, 32 bytes: `l_type` i16 @0, `l_whence` i16 @2, `l_start` i64 @8, `l_len` i64 @16, `l_pid` i32 @24. (`struct.pack("hhqqi4x", ...)` in Python.)
- `struct rlimit`, 16 bytes: `rlim_cur` u64 @0, `rlim_max` u64 @8. `RLIM_INFINITY` = 2**64 - 1. `RLIM_NLIMITS` = 16.
- `struct rusage`, 144 bytes: N1 3.2 (read by `PosixOsProc.rusage`).
- `struct passwd`, 48 bytes: `pw_name` ptr @0, `pw_passwd` ptr @8, `pw_uid` u32 @16, `pw_gid` u32 @20, `pw_gecos` ptr @24, `pw_dir` ptr @32, `pw_shell` ptr @40.
- `struct group`, 32 bytes: `gr_name` ptr @0, `gr_passwd` ptr @8, `gr_gid` u32 @16, `gr_mem` `char **` @24 (NULL-terminated).
- `struct termios` (glibc), 60 bytes: `c_iflag` u32 @0, `c_oflag` @4, `c_cflag` @8, `c_lflag` @12, `c_line` u8 @16, `c_cc[32]` @17, `c_ispeed` u32 @52, `c_ospeed` u32 @56. `NCCS` = 32.
- `struct winsize`, 8 bytes: `ws_row` u16 @0, `ws_col` @2, `ws_xpixel` @4, `ws_ypixel` @6.

### 3.3 Constants (decimal unless hex; the generated tables are the authority, these are anchors for review)
- fcntl: `F_DUPFD` 0, `F_GETFD` 1, `F_SETFD` 2, `F_GETFL` 3, `F_SETFL` 4, `F_GETLK` 5, `F_SETLK` 6, `F_SETLKW` 7, `F_DUPFD_CLOEXEC` 1030, `F_SETPIPE_SZ` 1031, `F_GETPIPE_SZ` 1032, `FD_CLOEXEC` 1, `F_RDLCK` 0, `F_WRLCK` 1, `F_UNLCK` 2, `LOCK_SH` 1, `LOCK_EX` 2, `LOCK_NB` 4, `LOCK_UN` 8; not module names but used by the runtime and the case: `F_OFD_GETLK` 36, `F_OFD_SETLK` 37, `F_OFD_SETLKW` 38.
- termios: `TCSANOW` 0, `TCSADRAIN` 1, `TCSAFLUSH` 2, `VINTR` 0, `VEOF` 4, `VTIME` 5, `VMIN` 6, `ICANON` 2, `ECHO` 8, `ISIG` 1, `B38400` 15, `TCGETS` 0x5401, `TIOCGWINSZ` 0x5413, `TIOCSWINSZ` 0x5414, `FIONREAD` 0x541B, `FIONCLEX` 0x5450, `FIOCLEX` 0x5451, `TIOCSCTTY` 0x540E (never used by the runtime). Check line of the case: 234 integer names whose sum is 3492074298.
- resource: `RLIMIT_CPU` 0, `FSIZE` 1, `DATA` 2, `STACK` 3, `CORE` 4, `RSS` 5, `NPROC` 6, `NOFILE` = `OFILE` 7, `MEMLOCK` 8, `AS` 9, `SIGPENDING` 11, `MSGQUEUE` 12, `NICE` 13, `RTPRIO` 14, `RTTIME` 15 (16 `RLIMIT_*` names; **no `RLIMIT_LOCKS`** on the reference builds although the kernel accepts resource 10); `RUSAGE_SELF` 0, `RUSAGE_CHILDREN` -1, `RUSAGE_THREAD` 1.
- open flags: `O_RDWR` 2, `O_NOCTTY` 256, `O_CLOEXEC` 524288. errno: `EPERM` 1, `ESRCH` 3, `EINTR` 4, `EIO` 5, `EBADF` 9, `EAGAIN` 11, `EACCES` 13, `EFAULT` 14, `ENOTDIR` 20, `EINVAL` 22, `ENOTTY` 25, `ERANGE` 34. `sysconf` names: `_SC_PAGESIZE` 30, `_SC_GETGR_R_SIZE_MAX` 69, `_SC_GETPW_R_SIZE_MAX` 70 (both give 1024).

### 3.4 Surface on the reference interpreters (`m1-31x.txt`; identical on the three lanes except 3.8)
- `fcntl`: `fcntl(fd, cmd, arg=0, /)`, `flock(fd, operation, /)`, `ioctl(fd, request, arg=0, mutate_flag=True, /)`, `lockf(fd, cmd, len=0, start=0, whence=0, /)`; 79 integer names.
- `termios`: `error`, `tcdrain(fd, /)`, `tcflow(fd, action, /)`, `tcflush(fd, queue, /)`, `tcgetattr(fd, /)`, `tcgetwinsize(fd, /)`, `tcsendbreak(fd, duration, /)`, `tcsetattr(fd, when, attributes, /)`, `tcsetwinsize(fd, winsize, /)`; 234 integer names.
- `resource`: `error`, `getpagesize()`, `getrlimit(resource, /)`, `getrusage(who, /)`, `prlimit(pid, resource, limits=None, /)`, `setrlimit(resource, limits, /)`, `struct_rusage`; 20 integer names (22 on 3.15).
- `pwd`: `getpwall()`, `getpwnam(name, /)`, `getpwuid(uidobj, /)`, `struct_passwd` (7 fields `pw_name, pw_passwd, pw_uid, pw_gid, pw_gecos, pw_dir, pw_shell`).
- `grp`: `getgrall()`, **`getgrgid(id)` and `getgrnam(name)` accept their argument by keyword** (unlike `pwd`), `struct_group` (4 fields `gr_name, gr_passwd, gr_gid, gr_mem`).
- `os`: `openpty()`, `ttyname(fd, /)`, `posix_openpt(oflag, /)`, `grantpt(fd, /)`, `unlockpt(fd, /)`, `ptsname(fd, /)` are in N2. Present in CPython and out of N2: 1.1 last bullet.
- Struct sequences in CPython can be constructed (`pwd.struct_passwd(range(7))`), pickled, copied, compared and hashed as tuples, sliced to a plain tuple, are read-only (`AttributeError: readonly attribute`) and have `n_fields`, `n_sequence_fields`, `n_unnamed_fields`, `__match_args__`, `__replace__`. Errors: `structseq() missing required argument 'sequence' (pos 1)`; `pwd.struct_passwd() takes a 7-sequence (3-sequence given)`.

### 3.5 Kernel and libc behaviour the cases rely on (measured)
- Two open file descriptions of one file conflict under `flock` inside one process (`LOCK_EX | LOCK_NB` -> `BlockingIOError(11)`); two shared locks coexist.
- A POSIX record lock of the process (`lockf`) is visible through an **OFD** query on another description of the same file in the same process: `fcntl(fd2, 36, flock)` returns `l_type` `F_WRLCK`/`F_RDLCK`, the locked range and `l_pid` = own pid; `fcntl(fd2, 37, ...)` -> `BlockingIOError(11)`. This is how `fcntl_basic` tests `lockf` without a second Python process (pyJVM cannot start one: `sys.executable` is not runnable).
- `lockf(LOCK_EX)` on a read-only descriptor -> `OSError(9)`. `ioctl(pipe, FIONREAD)` works; `FIOCLEX`/`FIONCLEX` toggle close-on-exec; an unknown request on a pipe is `ENOTTY` 25; an integer argument where a pointer is expected is `EFAULT` 14. Default pipe size 65536; `F_SETPIPE_SZ` 8192 returns 8192.
- A fresh pty: `tcgetattr` = `[1280, 5, 191, 35387, 15, 15, cc]`, `ECHO` and `ICANON` on, window size `(0, 0)`; the same attributes through the master; `TIOCSWINSZ` on either end is seen on both; with echo off a line written to the master is read from the slave and output written to the slave arrives as `b'out\r\n'`; after the master is closed `tcgetattr(slave)` is `error(5)` and `os.read(slave)` returns `b''`.
- `prlimit` on an own child changes only the child; lowering then raising its hard limit is `EPERM`; reading or writing pid 1's limits as non-root is `EPERM`; a missing pid `ESRCH`.

### 3.6 CPython behaviour to match (`m2-31x.txt`; identical on the three lanes unless 3.8 says otherwise)
The measured files are the reference; the lines below are the ones a reader would not guess.
fcntl:
- `fcntl(fd, F_GETFD)` 1 for a close-on-exec descriptor; `fcntl(fd, F_SETFD, True)` accepted; `fcntl(fd, 99999)` -> `OSError(22)`; a closed number `OSError(9)`; `fcntl.fcntl() takes no keyword arguments`; `fcntl expected at least 2 arguments, got 0`; `fcntl expected at most 3 arguments, got 4`.
- A `str` argument is encoded as UTF-8 and returned as bytes (`"\xe9"` -> `b'\xc3\xa9'`); `b""` -> `b''`; 1024 bytes are accepted on every lane.
- `flock(fd, 0)` and `flock(fd, 64)` -> `OSError(22)`; `flock` operation `"x"` -> the integer TypeError; `2**40` -> the C int OverflowError.
- `lockf(fd, 0)`, `lockf(fd, LOCK_NB)`, `lockf(fd, 64)` -> `ValueError: unrecognized lockf argument`; `len` as float -> `'float' object cannot be interpreted as an integer`; `whence=9` -> `OSError(22)`.
- `ioctl` with a mutable buffer returns the int result (0) and changes the buffer; with `mutate_flag=False` it returns bytes and leaves the buffer; a 2000-byte mutable buffer works.
- Audit: `fcntl.fcntl`, `fcntl.flock`, `fcntl.lockf`, `fcntl.ioctl`, argument tuples per 1.4, raised before the call (also when it then fails).
resource:
- `getrlimit(-1)`, `(16)`, `(99)` -> `ValueError: invalid resource specified`; `("x")` -> the integer TypeError; `(2**40)` -> the C int OverflowError; `resource.getrlimit() takes exactly one argument (0 given)`.
- `setrlimit`: texts of 1.7; items: str/float -> `'str' object cannot be interpreted as an integer`; `2**64` or more -> `OverflowError: Python int too large to convert to C rlim_t`; a hard limit of `2**63` -> the kernel's `EPERM` text; negative values per 3.8; a float is not truncated.
- `prlimit(0, r)`, `prlimit(os.getpid(), r)`, `prlimit(0, r, None)` equal `getrlimit(r)`; `prlimit(0, r, (soft, hard))` returns the old pair.
- `getrusage(RUSAGE_SELF)`: 16 fields, two floats then ints, `ru_maxrss > 0`; `RUSAGE_CHILDREN` and `RUSAGE_THREAD` work; `getrusage(5)`, `(-2)` -> `ValueError: invalid who parameter`.
- Audit: `resource.setrlimit` `(resource, limits)`, `resource.prlimit` `(pid, resource, limits or None)`; none for `getrlimit`, `getrusage`, `getpagesize`. `resource_basic` counts them (15 and 11), so the order "resource check, then audit, then limits" is pinned.
pwd / grp:
- Not found: `KeyError('getpwuid(): uid not found: 4000000123')`, `KeyError("getpwnam(): name not found: 'x'")` (the name through `repr`), `getgrgid(): gid not found: N`, `getgrnam(): name not found: 'x'`.
- `pwd.getpwuid`: `-1` and `2**32 - 1` -> `...not found: -1`; `-2`, `2**32`, `2**64`, `-2**70` -> `KeyError('getpwuid(): uid not found')` **without a number**; `"0"`, `0.0`, `None` -> `TypeError: uid should be integer, not str`; `False` is uid 0.
- `grp.getgrgid`: `-1` and `2**32 - 1` -> `KeyError('getgrgid(): gid not found: -1')`; `-2`, `2**32` and `-2**70` -> `OverflowError: gid is less than minimum` (as measured: also for `2**32`; the text is CPython's, not a typo here); `2**64` -> `OverflowError: gid is greater than maximum`; `"0"`, `0.0` -> `TypeError: gid should be integer, not str`. So the same out-of-range values give a number-less KeyError in `pwd` and an OverflowError in `grp`.
- Names: `b"root"` -> `TypeError: getpwnam() argument must be str, not bytes` and `getgrnam() argument 'name' must be str, not bytes`; NUL -> `ValueError: embedded null byte`.
- `getpwall()`/`getgrall()` return lists containing the current user/group and root, in a stable order.
termios / os:
- Texts of 1.8. `tcgetattr(-1)` -> the negative-descriptor ValueError; `tcgetattr("x")` -> the `fileno()` TypeError; an object with `fileno()` is accepted; `termios.tcgetattr() takes no keyword arguments`.
- `os.openpty(1)` -> `TypeError: posix.openpty() takes no arguments (1 given)`; `os.ttyname` of a pipe `OSError(25)`, of a closed number `OSError(9)`; `os.ptsname(pipe)` `OSError(25)`; `os.grantpt(pipe)` `OSError(22)`; `os.posix_openpt(-1)` -> `NotADirectoryError(20)` (every flag bit set; comes from the kernel).
- `tty.setraw(fd)` returns the previous attributes and clears `ECHO | ICANON | ISIG | IEXTEN` and `OPOST`; `pty.openpty()` works.
- No audit events in `termios`, `pwd`, `grp`, `os.openpty`, `os.ttyname`.

### 3.7 What the draft cases cover
`E:\pyjvm-work\n2\cases\`: `fcntl_basic` (102 lines of output), `resource_basic` (78), `pwd_grp_basic` (56), `termios_pty` (93). Each prints `os.name` first and does everything else under `if os.name == "posix":`. They print no descriptor numbers, pids, limit values or usage numbers, and nothing that depends on the JVM's raised `RLIMIT_NOFILE`. Their safety properties are stated in their header comments and must survive any edit.

### 3.8 Lane-dependent items (every row measured on all three lanes)
| Item | 3.13 | 3.14 | 3.15 |
| --- | --- | --- | --- |
| `fcntl.fcntl` third argument bytearray / memoryview | `TypeError: 'bytearray' object cannot be interpreted as an integer` | accepted, returns bytes | as 3.14 |
| `fcntl.fcntl` buffer longer than 1024 | `ValueError: fcntl string arg too long` | `ValueError: fcntl argument 3 is too long` | accepted (returns bytes of that length) |
| `fcntl.ioctl` read-only (or non-mutated) buffer longer than 1024 | `ValueError: ioctl string arg too long` | `ValueError: ioctl argument 3 is too long` | accepted |
| third argument of a wrong type (float, None) | `TypeError: 'float' object cannot be interpreted as an integer` | `TypeError: fcntl() argument 3 must be an integer, a bytes-like object, or a string, not float` (`ioctl()` likewise) | as 3.14 |
| `ioctl` request of a wrong type | `'str' object cannot be interpreted as an integer` | `TypeError: ioctl() argument 2 must be int, not str` | as 3.14 |
| int third argument or request outside 32/64 bits (`2**32`, `2**64`) | accepted silently | accepted silently | accepted with `DeprecationWarning: integer value out of range` (which values warn: rerun `m2.py` with `-W error::DeprecationWarning`; measured: `fcntl` arg `2**32` and `2**64`, `ioctl` request `2**64`) |
| `fileno()` returning a non-int | `TypeError: fileno() returned a non-integer` | same | `TypeError: F.fileno() must return an int, not str` (class name, result type) |
| `resource.RLIM_INFINITY` and how an infinite limit is returned | `-1` | `-1` | `18446744073709551615` |
| `resource.RLIM_SAVED_CUR`, `RLIM_SAVED_MAX` | absent | absent | present, `18446744073709551615` |
| a limit of `-1` passed to `setrlimit` | infinity, silently | infinity, silently | infinity, with `DeprecationWarning: Use RLIM_INFINITY instead of negative limit value.` |
| `grp.getgrgid()` argument-count texts | `function missing required argument 'id' (pos 1)`, `function takes at most 1 argument (2 given)` | `getgrgid() missing required argument 'id' (pos 1)`, `getgrgid() takes at most 1 argument (2 given)` | as 3.14 |
| `tcsetattr` cc item of a wrong type | `TypeError: tcsetattr: elements of attributes must be characters or integers` | `TypeError: tcsetattr: elements of attributes must be bytes objects of length 1 or integers` | as 3.14 |
Other negative limits (`-2`) give `ValueError: Cannot convert negative int` in `m2-31x.txt` on all three lanes for the hard-limit position; the resource ticket reruns the negative-value lines for the soft position on each lane before coding (U4).

### 3.9 CPython's own tests: what each needs (static inventory, corpus `5b28ebd`)
| Test module | Tests | Needs / hazard |
| --- | --- | --- |
| `test_pwd`, `test_grp` | 3 + 3 | read-only; `test_values_extended` walks every entry |
| `test_resource` | 14 | **changes `RLIMIT_FSIZE` soft in-process** (`test_fsize_ismax`, `_enforced` (1024 bytes, then writes a file), `_too_big`, `_not_too_big`, `_negative`); `test_prlimit` on pid 0 with `RLIMIT_AS` (writes the same values back); `test_setrusage_refcount` passes a bad sequence for `RLIMIT_CPU` (rejected before the call) |
| `test_fcntl` | 14 | `test_lockf_exclusive`/`_share` use `multiprocessing.Process` (blocked: fork/sys.executable); two need `_testcapi`; `F_SETOWN_EX`/`F_NOTIFY` tests use buffers; `test_fcntl_f_pipesize` |
| `test_ioctl` | 10 | `IoctlTestsTty` opens `/dev/tty` (skips without a controlling terminal: 2.3 A6); `IoctlTestsPty` uses `os.openpty`, `TCFLSH`, `TCXONC`, `TIOCSWINSZ`, and threads |
| `test_termios` | 21 | `os.openpty` per test; threads in the flush/flow tests |
| `test_tty` | 4 | `os.openpty` |
| `test_openpty` | 1 | `os.openpty` |
| `test_pty` | 6 | `pty.fork`, `os.setsid`, `pty.spawn`: blocked (fork); `test_openpty` and `test_master_read` use `pty.openpty` and a SIGHUP handler; `SmallPtyTests` mock `os`/`tty` |
| `test_getpass` | 50 | mocks `termios`/`os.open`; no real terminal with A6 |
| `test_subprocess` (parts) | - | `test_pipesize_default`, `test_pipesizes` (`F_GETPIPE_SZ`); `test_no_leaking` (**soft `RLIMIT_NOFILE` 1024, then opens descriptors until exhaustion**); `test_preexec_fork_failure` (**soft `RLIMIT_NPROC` 0: never run**, 2.3 B6); user/group tests (`pwd.getpwnam`, `grp.getgrnam`; still skipped: `os.setreuid` absent); `test_close_fds_when_max_fd_is_lowered` (child of `sys.executable`: blocked) |
| `test_os`, `test_shutil`, `test_pathlib`, `test_tarfile`, `test_posixpath` | - | users of `pwd.getpwuid`/`getpwall`, `grp.getgrgid`, `fcntl.fcntl(F_GETFD)`: previously skipped or failing on the shells |

*Correction to the `test_resource` row (2026-11-26, found by ticket 8, recorded by ticket 9; the row above is left as written):* `test_setrusage_refcount` is **not** rejected before the call. Its `BadSequence` converts to `(1000000, 1000000)`, so the test lowers the **hard** `RLIMIT_CPU` of the process that runs it to 10^6 s. `test_prlimit_refcount` sets `RLIMIT_AS` soft and hard to `RLIM_INFINITY - 1` (`2**64 - 2` on 3.15), which lowers the hard limit too. Both violate 2.3 B1 inside a JVM, so ticket 8 did not run them under pyJVM (label "unsafe: not run"); the same objects passed to `resource.prlimit(child, ...)` on a `sleep 30` child give identical results on CPython and pyJVM (`work/n2/cpython_tests.md`; setrlimit and prlimit share the converter). Any later run of `test_resource` selects everything except these two tests and the five `test_fsize_*` tests (those one per fresh JVM).

## 4. Review checklist that applies to every ticket (Opus)
1. **Windows untouched.** No file named `Windows*` is in the diff. Shared files changed only as the ticket names; every such line is POSIX-only by construction (`IoFd.KERNEL`, a `POSIX_ONLY` module, or an added method nothing on Windows calls).
2. **Class loading.** No `PosixLibcConsts`, `PosixArgs`, `PosixFcntlModule`, `PosixResourceModule`, `PosixAccounts`, `PosixOsTty`, `PosixTermiosModule` reference is reachable on Windows; `test_windows_never_loads_posix_ffm` lists them and passes. No `Windows*` reference from the new classes.
3. **Every libc call checks its result.** errno only after a failing return and from that call's captured state; the `_r` functions by return value, never errno; a NULL result pointer is "not found", not a crash.
4. **Native memory.** No heap-backed pointer reaches libc; buffer arguments are copied into a native buffer of at least 1024 bytes (the kernel may write more than the caller's object holds); a struct is read only after a successful call; strings are copied before the next libc call and, for `getpwent`/`getgrent`, inside `ENT_LOCK`; `endpwent`/`endgrent` in `finally`; no segment outlives its Arena.
5. **Descriptors.** `os.openpty`, `os.posix_openpt` and `F_DUPFD*` results are `adopt`ed; everything except plain `F_DUPFD` is close-on-exec; **every terminal open has `O_NOCTTY`**; no descriptor leaks on a failure path.
6. **Terminal safety (2.3 A).** Read every terminal call in every test, case and scratch script in the diff: only on a pty the script opened; under `timeout`; no echo read after a flush.
7. **Limit safety (2.3 B).** Read every `setrlimit`/`prlimit` call in the diff: only the permitted changes, restored in `finally`; no hard limit of a runner lowered; root guarded.
8. **Signals and blocking.** N1 2.3 holds. Lock waits go through `lockWait`; `dispatchPending()` never between a call and its copy-out.
9. **No placeholders.** Nothing returns a made-up value; the `StubModules` fakes are gone; what cannot work is absent or raises.
10. **Lanes.** Every lane-dependent text or value is one of 3.8 and selected with `LangVersion`; warnings go through Python `warnings`.
11. **Audit events** have the measured names, argument tuples and position relative to the argument checks.
12. **Method size / data**: tables are generated arrays (rerun the generator and diff); `methodsize` 0 over 8000. The acceptance logs exist for both OSes and the counts in the report match them.

## 5. Tickets

### Ticket 0. Branch, evidence in the repo, constant generator, brief, baseline (no runtime change)
Implementer tier: **sonnet**. Copying, one generator extension and a by-name baseline (not haiku: the baseline comparison must be right).
Precondition: `n1-proc` is at `7c9010f` with a clean tree on both clones.
Do:
1. Create `n2` from `7c9010f` (Windows repo; WSL `cd ~/pyjvm-n0 && git fetch win && git checkout n2`).
2. Commit under `work/n2/`: `measure/` = `m1.py`, `m2.py`, `layout.c`, `layout.txt`, `run1.sh`, `run2.sh`, `run3.sh`, `run4.sh`, `runcases.sh` and the six `m*-31x.txt` outputs from `E:\pyjvm-work\n2\`; `cases/` = the four draft cases and their twelve `-31x-1.out` files. Do not edit them (they are this plan's evidence). SPDX headers only if `tests/test_licenses.py` covers `work/` (check, as N1 ticket 0 did).
3. `tools/posix_measure_n2.py`: `m1.py` and `m2.py` concatenated with the same output format, keeping the SAFETY comment and the root guard of `m2.py` at the top. `tools/gen_posix_tables.py`: add a `--consts MODULE` mode that prints, for `fcntl`, `termios` or `resource` under Linux CPython, two Java array initialisers (names sorted, `long` values parallel) of every integer name **except** `RLIM_INFINITY`, `RLIM_SAVED_CUR`, `RLIM_SAVED_MAX`.
4. Rerun `tools/posix_measure_n2.py` on the three lanes (new session, stdin `/dev/null`, `timeout 120`) and diff against the committed outputs: pids, descriptor numbers in audit tuples and memory addresses differ; nothing else may. Run the generator on the three lanes: the three outputs must be identical per module. If the gates will run on the glibc 2.35 host, compile and run `layout.c` there and diff against 3.2.
5. Write `E:\pyjvm-work\n2\brief_common.md` with the text of 2.4.
6. Baseline `work/n2/baseline.md` in the shape of `work/n1/baseline.md`: N1 ticket 12's totals and failing/skipped case names per lane and OS (HANDOFF entry "2026-11-25: N1 ticket 12"; WSL logs `~/pyjvm-work/logs/pyjvm-n0-25d8e3b/`; Windows logs `E:\pyjvm-work\n1\logs\t12\`). Expected: Linux pytest 165 passed / 3 skipped, methodsize 0 of 5041, conformance 3.15 407 / 2 / 3, 3.14 393 / 1 / 18, 3.13 384 / 1 / 27 (412 cases), failing `time_struct_time_and_strftime` (all lanes) and `zlib_streams` (3.15), strict 808 PASS; Windows pytest 134 passed / 34 skipped, conformance 409 / 394 / 385 passed, 0 failed. State that the gates ran at `25d8e3b` plus the documentation diff, not at `7c9010f` itself. If the logs no longer give case names, rerun L-full and W-full at `7c9010f` (one gate at a time, under the lock).
Acceptance: branch exists on both clones; `work/n2/` committed; the measurement rerun shows no difference beyond the allowed ones; the generator's output is lane-independent; `baseline.md` lists failing and skipped cases by name.
Review checklist: nothing under `runtime/` or `tests/` changed; `tools/posix_measure_n2.py` obeys 2.3 (read every terminal, limit and `kill` call); the generator excludes the three per-lane names; the baseline is by case name and says which tree it was taken at.

### Ticket 1. Shared plumbing and JVM-side probes
Implementer tier: **sonnet**. Small helpers with measured rules, plus a scratch probe with explicit stop conditions.
Files: new `PosixLibcConsts.java` (generated), new `PosixArgs.java`, `PosixStructSeq.java`, `tests/test_import_runtime.py` (the seven class names of checklist item 2 added to `POSIX_FFM_CLASSES`), new `tests/test_posix_libc.py`, `tests/conformance/os_platform.py` (Windows branch, 1.9), `work/n2/probe.md`.
Do:
1. **Probe first** (scratch Java in `~/pyjvm-work/n2/probe/`, plain `javac`/`java --enable-native-access=ALL-UNNAMED`, no pyJVM code; this runs a JVM: take the run lock; start it with `setsid ... < /dev/null` under `timeout 120`), handles built per 1.2:
   a. `getpwuid_r(getuid())` and `getpwnam_r("root")` return entries; a missing uid gives a NULL result; `getpwent` enumeration from two Java threads under a lock gives the same count as `getent passwd | wc -l`; no crash, no hs_err (U1: NSS inside the JVM).
   b. `getrlimit(RLIMIT_NOFILE)` in the JVM: record soft and hard (expected `soft == hard`); `setrlimit` soft minus one and back succeeds. **No other limit is touched.**
   c. `posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC)`, `grantpt`, `unlockpt`, `ptsname_r`, `open(O_RDWR | O_NOCTTY | O_CLOEXEC)`: `tcgetattr` on the slave returns the values of 3.5; field 7 (`tty_nr`) of `/proc/self/stat` is 0 before and after (no controlling terminal acquired although the JVM leads its session); closing the master delivers no SIGHUP (the JVM is still alive after one second).
   d. `flock(fd, LOCK_EX)` on one description, then on a second description of the same file from another Java thread: it blocks; released when the first unlocks; with `LOCK_NB` it returns `EAGAIN`.
   Output: `work/n2/probe.md`. **Stop and report to the planner** if (a) crashes or enumerates nothing, (c) shows a non-zero `tty_nr` or the JVM dies, or (b) shows `setrlimit` failing.
2. `PosixLibcConsts`: `FCNTL_NAMES`/`FCNTL_VALUES`, `TERMIOS_*`, `RESOURCE_*` from `tools/gen_posix_tables.py --consts`; header names the generator. No FFM import. One static helper `static void install(PyModule m, String[] names, long[] values)`.
3. `PosixArgs`: `static int fd(Object)` per 1.3; `static byte[] bufferArg(...)`-style helpers are added by ticket 4, not here.
4. `PosixStructSeq`: `cls(sequence)` builds an instance (any iterable of exactly `n_fields` items; errors of 3.4); `__reduce__` returns `(cls, (tuple(self),))` so `pickle` and `copy` work. `__replace__` is not added (recorded gap). Applies to `os.waitid_result` and `resource.struct_rusage` too. Before coding, measure on the three lanes with a scratch script (added to `work/n2/measure/`): construction from a list, a generator, a too-long sequence, with a second `dict` argument; `pickle` round trip; `copy.copy`.
5. `tests/test_posix_libc.py` (Linux only, skipped on Windows; each test runs pyJVM on a small script and, where it says so, the same script under CPython and compares): in this ticket, structseq construction, `pickle` and `copy` through `os.waitid_result((1, 2, 3, 4, 5))` (the only public `PosixStructSeq` type that exists before ticket 2), compared with CPython. Later tickets append their pyJVM-specific tests to this file.
6. `os_platform.py`: the Windows line of 1.9. (On Linux the file prints what it printed before.)
Acceptance: `probe.md` exists with every item answered and the stop conditions evaluated; W-min (the changed `os_platform` passes on the three Windows lanes); L-min and N-min on three lanes; `test_posix_libc` passes; `test_import_runtime` passes on Windows with the longer list (the classes that do not exist yet are simply not in the log).
Review checklist: the tables are generated (rerun and diff); `PosixArgs.fd` matches all eight "fd ..." lines of `fcntl_basic`'s reference output on each lane, including 3.15's class-name text; the probe obeyed 2.3 (read it); nothing but `probe.md` from the probe is in the repo; `os_platform`'s POSIX output is unchanged; structseq construction does not change `repr`, equality or the existing `os_wait_kill`/`os_wait_rusage` outputs.

### Ticket 2. `resource`
Implementer tier: **sonnet**. Four libc calls with fully measured rules; the care is in the safety of its own tests.
Files: new `PosixResourceModule.java`, `ImportSupport.java` (the `resource` arm), `StubModules.java` (`resource()`: `getrlimit`, `setrlimit`, `getpagesize` move into `unsupported(...)`; class comment), new `tests/conformance/resource_basic.py`, `tests/test_posix_libc.py`.
Do: 1.7 and 3.6 "resource", 3.8. Before coding, rerun the negative-value lines of `m2.py` for both tuple positions on each lane (3.8 last paragraph) and add the output to `work/n2/measure/`. `resource_basic.py` = the draft unchanged. pytest additions: (a) under `PYJVM_POSIX_FDS=nio`, `resource.getrlimit(0)` raises NotImplementedError and `import resource` still works; (b) `type(os.wait3(os.WNOHANG)[2]) is resource.struct_rusage` in kernel mode (one class object); (c) 3.15 only: `resource.RLIM_SAVED_CUR` exists.
CPython reference: 3.6 "resource"; `resource_basic-31x-1.out`.
Acceptance: `resource_basic` on Linux three lanes, 3 runs on 3.15; `os_wait_rusage` unchanged; L-min, N-min; W-min plus the case on Windows (prints `nt`); `tools/resumable.py` and `test.support` still import (`tests/test_resumable_runners.py`, `support_helpers` case).
Review checklist: 2.3 B for the case and the pytest (read every `setrlimit`/`prlimit` call; the soft `RLIMIT_NOFILE` is restored in every path of the pyJVM-only tests too); `EPERM` maps to ValueError for `setrlimit` and to PermissionError for `prlimit`; the audit event comes after the resource check and before the limits are parsed (the case's counts 15 and 11); infinity is `-1` on 3.13/3.14 and `2**64 - 1` on 3.15, in both directions; the `rusage` buffer is zeroed and read by `PosixOsProc.rusage` (no second reader); the 16-byte `struct rlimit` is written as two unsigned 64-bit values; `StubModules.resource` returns no made-up value any more.

### Ticket 3. `pwd` and `grp`
Implementer tier: **sonnet**. Pointer-returning libc calls with a buffer-growth loop; all rules measured.
Files: new `PosixAccounts.java`, `ImportSupport.java` (the `pwd` and `grp` arms), `StubModules.java` (`getpwall`, `getgrall` into `unsupported(...)`; class comment), new `tests/conformance/pwd_grp_basic.py`, `tests/test_posix_libc.py`.
Do: 1.6 and 3.6 "pwd / grp", 3.8. `pwd_grp_basic.py` = the draft unchanged. pytest additions: (a) the buffer-growth path: call the package-private lookup with an initial buffer of 16 bytes through a test hook system property (`pyjvm.test.pwbuf`, read once; default 0 = use `sysconf`) and compare `pwd.getpwuid(0)` and `grp.getgrgid(0)` with the normal result; (b) eight threads each calling `pwd.getpwall()` and `grp.getgrall()` 20 times get equal lists; (c) `nio` mode: `pwd.getpwall()` raises NotImplementedError.
CPython reference: 3.6; `pwd_grp_basic-31x-1.out`.
Acceptance: `pwd_grp_basic` on Linux three lanes; L-min, N-min; W-min plus the case on Windows; the pytest additions; then one line each, compared with CPython by hand and put in the report: `posixpath.expanduser("~root")`, `pathlib.Path("/").owner()`, `pathlib.Path("/").group()`, `getpass.getuser()` with `LOGNAME`, `USER`, `LNAME` and `USERNAME` removed from the environment.
Review checklist: the `_r` functions are judged by return value and the result cell, never errno; `ERANGE` doubles the buffer and nothing else does; every string is copied before the buffer's Arena closes; the enumeration holds `ENT_LOCK` from `setpwent` to `endpwent` and `endpwent` is in `finally`; ids are unsigned with the `2**32 - 1` -> `-1` rule; the uid and gid converters differ exactly as the reference outputs show (do not unify them); names are encoded with surrogateescape and a NUL is rejected before any libc call; `gr_mem` is a list; no Java-side parsing of `/etc/passwd` anywhere.

### Ticket 4. `fcntl`: `fcntl`, `flock`, `lockf`, the lock-wait rule
Implementer tier: **opus**. Native buffers the kernel writes into, descriptor creation by `F_DUPFD`, the signal interplay of blocking locks; a mistake is memory corruption, a leaked descriptor or an uninterruptible main thread.
Files: new `PosixFcntlModule.java`, `PosixArgs.java` (buffer helpers), `PosixProc.java` (the back-off of `await` exposed as a package-private helper, behaviour unchanged), `ImportSupport.java` (the `fcntl` arm), `StubModules.java` (class comment only; the `fcntl` shell stays for `nio`), `tests/test_posix_libc.py`, `work/n2/t4_check.py`.
Do: 1.4 without `ioctl` (ticket 5 adds it; register nothing under that name yet in kernel mode, so `hasattr(fcntl, "ioctl")` is False for this ticket only), 1.5, 3.6 "fcntl", 3.8.
- `fcntl_basic.py` needs `ioctl`, so this ticket's check is `work/n2/t4_check.py`: every line of the draft above its `# ioctl on a pipe` comment plus the final audit lines restricted to the three functions; run under CPython and pyJVM on the three lanes and diffed.
- pytest additions (compared with CPython where marked): (a) a descriptor made by `F_DUPFD` or `F_DUPFD_CLOEXEC` is adopted: it is listed in `/proc/self/fd`, `os.closerange(fd, fd + 1)` closes it (N0 ticket 12: `closerange` skips numbers pyJVM never adopted), and a dropped file object wrapping it does not close a reused number (the N0 generation guard: reuse `test_posix_fds`'s technique); (b) main thread blocked in `fcntl.flock(fd2, LOCK_EX)` while a helper thread holds the lock on another description for 0.5 s: a `SIGALRM` handler set with `setitimer(0.1)` runs before the lock is acquired, the lock is acquired afterwards (CPython compared); (c) the same with a handler that raises: the exception propagates and a following `flock(fd2, LOCK_EX | LOCK_NB)` still reports the conflict; (d) the same wait from a non-main thread acquires the lock (one blocking call); (e) `lockf(fd, LOCK_EX)` blocking against an OFD lock held on another description and released by a thread after 0.3 s; (f) descriptor count unchanged after 1000 `fcntl(fd, F_GETFD)` calls and 100 buffer calls.
CPython reference: 3.6 "fcntl"; `fcntl_basic-31x-1.out`.
Acceptance: `t4_check.py` output identical to CPython on three lanes; the pytest additions; `os_fd_flags`, `os_pipe`, `subprocess_posix` unchanged (L-min, N-min); W-min; in `nio` mode `fcntl.fcntl` still raises NotImplementedError.
Review checklist:
- the buffer path never passes less than 1024 native bytes and returns exactly the caller's length; the per-lane length and type rules of 3.8, including 3.13 rejecting bytearray;
- `F_DUPFD*` results are adopted only on success and only for those two commands with an integer argument;
- `lockWait` is used for exactly `flock` without `LOCK_NB`, `F_SETLKW` and 38, for both the buffer form of `fcntl.fcntl` and `lockf`; the non-waiting substitute is the right command (6 for 7, 37 for 38); `EAGAIN` and `EACCES` are the only "try again" results; the back-off is `PosixProc`'s code, not a copy; `os_wait_signals` still passes (the helper extraction changed nothing);
- `lockf` builds `struct flock` per 3.2 and maps `cmd` exactly (the three ValueError inputs of 3.6);
- audit tuples per 1.4 (`None` for omitted arguments);
- 2.3 D for the tests: every waiter has a holder that releases, joins have timeouts, pytest subprocess calls have `timeout=`;
- `PosixFds.java` is not in the diff.

### Ticket 5. `fcntl.ioctl`
Implementer tier: **sonnet**. One function on ticket 4's buffer helpers; the write-back and length rules are measured.
Files: `PosixFcntlModule.java`, new `tests/conformance/fcntl_basic.py`, `tests/test_posix_libc.py`.
Do: `ioctl` per 1.4, 3.6, 3.8. `fcntl_basic.py` = the draft unchanged (it uses literal request numbers, so it does not need `termios`). pytest: `array.array("i")`, `bytearray` and a writable `memoryview` slice as mutable targets; a read-only `memoryview`; the request values `-1`, `2**31`, `2**32` reach the kernel as the same 32-bit number (all give `OSError(25)` on a pipe, compared with CPython).
Acceptance: `fcntl_basic` on Linux three lanes, 3 runs on 3.15; `work/n2/t4_check.py` still equal; L-min, N-min; W-min plus the case on Windows.
Review checklist: the mutable path copies back exactly `len` bytes and returns the int; `mutate_flag=False` and read-only buffers return bytes and leave the object untouched; a mutable buffer of any length is accepted, a read-only one over 1024 follows 3.8; the request is zero-extended after truncation (a negative Python int must not become a 64-bit value with the high half set); the native buffer is at least 1024 bytes plus guard for every form; no `ioctl` request is special-cased.

### Ticket 6. The `os` pty functions
Implementer tier: **sonnet**. Five libc calls and one composed open; the controlling-terminal rule is the review point.
Files: new `PosixOsTty.java`, `PosixOsProc.java` (last line of `install`: `PosixOsTty.install(m);`), `tests/test_posix_libc.py`, `work/n2/t6_check.py`.
Do: 1.8 first two bullets, 3.6 "termios / os". `termios_pty.py` needs `termios`, so this ticket's check is `work/n2/t6_check.py`: the `openpty`, `ttyname`, `posix_openpt`/`grantpt`/`unlockpt`/`ptsname` lines of the draft, plus data both ways through the pair in its default mode (`os.write(slave, b"out\n")` -> master reads `b'out\r\n'`; master writes `b"line\n"`, slave reads it and the master reads the echo `b'line\r\n'`), `os.get_terminal_size(slave)` on the fresh pty (window size `(0, 0)`: print whatever CPython prints) and after `fcntl.ioctl(master, 0x5414, struct.pack("HHHH", 24, 80, 0, 0))` (measured `(80, 24)`), `os.isatty`, `select.select` on the master, and `subprocess.run(["sh", "-c", "tty; test -t 0 && echo yes"], stdin=slave, stdout=subprocess.PIPE, timeout=60)` (the child sees a terminal; print only whether the output starts with `/dev/pts/` and ends with `yes`). Run under CPython and pyJVM on three lanes under `timeout 300`, in its own session.
pytest: (a) after `os.openpty()` in a pyJVM that leads its own session (the test starts it with `start_new_session=True`), field 7 of `/proc/self/stat` is 0; (b) both descriptors are in `/proc/self/fd`, non-inheritable, and gone after `os.close`; 200 open/close rounds leave the descriptor count unchanged; (c) a `close_fds=False` child does not see either end (close-on-exec).
Acceptance: `t6_check.py` equal to CPython on three lanes; the pytest additions; `os_terminal_size`, L-min, N-min; W-min (`hasattr(os, "openpty")` is False on Windows: the `os_platform` line of ticket 1).
Review checklist: **`O_NOCTTY` and `O_CLOEXEC` on both opens**, no libc `openpty`; both descriptors adopted, and the master closed when a later step fails (force it in a scratch run: `ptsname_r` with a 1-byte buffer must grow, not fail); `ttyname_r`/`ptsname_r` judged by return value; `posix_openpt` adds `O_CLOEXEC` to the caller's flags; `PosixOsProc.install` gained exactly one line; 2.3 A for the check script and tests (read every line that touches a terminal; the `sh` child has `timeout=`).

### Ticket 7. `termios`
Implementer tier: **sonnet**. Struct marshalling with measured rules on ticket 6's pty.
Files: new `PosixTermiosModule.java`, `ImportSupport.java` (`POSIX_ONLY` gains `"termios"`; the `termios` arm per 1.9), new `tests/conformance/termios_pty.py`, `tests/test_posix_libc.py`.
Do: 1.8 third bullet onward, 1.3 (`termios.error`), 3.6, 3.8. `termios_pty.py` = the draft unchanged. Before coding, measure with a scratch script on the three lanes (add to `work/n2/measure/`) the order of `tcsetattr`'s checks that the case does not pin: a pipe together with a bad attribute list; a bad `when` together with a bad `cc`. pytest: (a) `nio` mode: `import termios` raises ModuleNotFoundError; (b) `tty.setraw` then restoring with the returned list round-trips; (c) `getpass.getpass` is **not** called; instead `getpass.unix_getpass` is checked to be importable and `termios.tcgetattr`'s list is accepted unchanged by `termios.tcsetattr` on a fresh pty for each of `TCSANOW`, `TCSADRAIN`, `TCSAFLUSH`.
Run `termios_pty` alone under `timeout 300` in its own session first (2.3 A5), three times per lane, before any set.
Acceptance: `termios_pty` on Linux three lanes, 5 runs on 3.15 without a hang; `t6_check.py`, `fcntl_basic`; L-min, N-min; W-min plus the case on Windows (prints `nt`; `import termios` on Windows is ModuleNotFoundError: the `os_platform` line); `import tty, pty, getpass, _pyrepl.fancy_termios` succeed on Linux.
Review checklist: `termios.error` is one class per module, base `Exception`, args `(errno, text)`, not an `OSError`; the 60-byte struct is zeroed, filled by `tcgetattr` before `tcsetattr` modifies it, and the speeds go through `cfset*speed`; `cc` items follow the `ICANON` rule on read and the bytes-or-int rule on write; `tcsetwinsize` keeps the pixel fields; in `nio` mode and on Windows the module does not exist; **no call in the case, tests or scratch touches a terminal the script did not open** (read all of them); the case kept its `select` before the flush and reads no echo after it.

### Ticket 8. CPython's own tests, performance, triage
Implementer tier: **opus**. Open-ended diagnosis with tests that can damage the runner if run carelessly; a wrong "blocked" label hides a real N2 bug.
No design change expected; fixes only inside the scope of tickets 1-7, each its own small commit with a conformance or pytest line that fails before the fix. Anything larger is listed, not fixed.
Run on Linux, 3.15 lane, through `tools/run_cpython_test.py` (corpus `~/cpython/Lib`), every run under the lock, under `timeout`, in its own session with stdin `/dev/null` and SIGHUP/SIGQUIT reset, leftover processes of that session killed by pid afterwards (N1 ticket 11's `cpy.sh`, which refuses to run outside `setsid`):
1. Whole modules: `test_pwd`, `test_grp`, `test_openpty`, `test_tty`, `test_termios`, `test_ioctl`, `test_fcntl`, `test_pty`, `test_getpass`.
2. `test_resource`: first every test **except** the five `test_fsize_*` (`-k` selection); then the `test_fsize_*` tests one per fresh JVM, after a warm-up run of the same command has filled the caches, with stdout through a pipe (2.3 B6). Record each result whatever it is; a JVM that dies or hangs is a result ("fails: JVM cannot run under soft `RLIMIT_FSIZE` 1024"), not a reason to retry differently.
3. `test_subprocess`: `test_pipesize_default`, `test_pipesizes`, the `user`/`group`/`extra_groups` tests, `test_close_fds_when_max_fd_is_lowered`; then `test_no_leaking` **alone in a fresh JVM, last, under `timeout 600`**. Never `test_preexec_fork_failure`.
4. Users elsewhere, by `-k`: `test_posixpath` `expanduser` tests; `test_pathlib` `owner`/`group`/`expanduser` tests; `test_shutil` `chown` and archive owner/group tests; `test_os` tests that use `fcntl.fcntl(F_GETFD)` (the inheritable-flag tests) and `pwd`; `test_tarfile` tests naming `pwd`/`grp`.
Output: `work/n2/cpython_tests.md`: per module passed/failed/errors/skipped under pyJVM and CPython, and for every differing test one line: cause and one of {fixed here, blocked: fork, blocked: sys.executable, blocked: `_testcapi`, blocked: controlling terminal (2.3 A6, skipped on both), blocked on N3, out of scope with reason, unsafe: not run}.
Performance (sequential, warm, three rounds alternating with CPython): (a) 20 000 x `fcntl.fcntl(fd, F_GETFD)`; (b) 2 000 x `pwd.getpwuid(os.getuid())`; (c) 200 x `pwd.getpwall()`; (d) 2 000 x `termios.tcgetattr(slave)`; (e) 2 000 x `resource.getrlimit(RLIMIT_NOFILE)`.
Acceptance: the report exists; nothing unclassified; no timing is more than 10x CPython's or the numbers are reported to the planner (a report, not a redesign by the implementer).
Review checklist: the run scripts obey 2.3 (read them: session, `/dev/null`, timeouts, the never-run test is deselected by name, `test_no_leaking` and the `fsize` tests isolated); every "fixed here" has a test line; "blocked" labels are plausible (the test really calls what the label names); no fix touches shared code; no hs_err file left; no leftover process.

### Ticket 9. Documentation and final gates
Implementer tier: **sonnet**. Accurate docs from this plan and the tickets' results, then the full gates (not haiku: the limits must be stated correctly).
Files: `JVM_BACKEND.md` ("POSIX native layer": new subsection "Small libc modules (N2: fcntl, termios, resource, pwd, grp, ptys)": what exists; the buffer rule; the lock-wait rule and what a polled wait loses (1.5); other blocking calls are not sliced; record locks and JDK-internal opens (1.4); `F_DUPFD` adoption; `resource` reports the JVM's limits, `RLIMIT_NOFILE` soft equals hard, and which limits can stop the JVM (1.7); NSS through libc, enumeration lock; `os.openpty` is not libc `openpty` and never takes a controlling terminal; `termios.error`; the absent functions of 1.1; structseq construction/pickle present, `__replace__` absent; the `nio` shells; ticket 8's counts; the 2.3 warnings for anyone writing tests), "Known gaps" under "unittest and CPython's test modules" (the shells sentence is no longer true), `HANDOFF.md` (work-log entry; queue item N2 done), `STDLIB_STATUS.md` and `tools/stdlib_manifest.json` (regenerate on Linux; expected: the 4 `missing-native: termios` rows move: `pty`, `tty`, `_pyrepl.fancy_termios` up, `_pyrepl.unix_console` to its next missing module; state what actually changed), `work/native_modules_plan.md` (N2 status, pointer here, obligations for N3 and N6: `PosixArgs.fd`, the buffer helper, `lockWait`, `os.openpty` for curses testing), class comments of `StubModules` (which shells remain and only for `nio`).
Then L-full and W-full; compare with `work/n2/baseline.md`.
Acceptance: Linux: every case that passed at baseline passes, the failing set is the baseline's or smaller, and `resource_basic`, `pwd_grp_basic`, `fcntl_basic`, `termios_pty` pass on three lanes. Windows: identical pass/fail sets to baseline on three lanes plus the four new cases passing (first line only) and the changed `os_platform` passing; pytest and methodsize unchanged apart from the new Linux-only tests skipping on Windows.
Review checklist: no doc claims something the tests do not show; the limits list is complete (1.4 last bullet, 1.5, 1.7 last bullet, 1.8 last bullet, 1.9, U5-U8); Windows results equal baseline by case name; comment-only changes in shared Java files do not alter code; a case that newly behaves differently because a stdlib module now takes its real `fcntl`/`termios`/`pwd` path instead of a fallback is explained, not waved through.

## 6. Risks
1. **No JVM-side fact was measured** (U1). Mitigation: ticket 1's probe with stop conditions before runtime code depends on them.
2. **Tests that can hurt the runner**: a terminal call on the wrong descriptor, a lowered limit in a JVM, a pty read that never returns. Mitigation: 2.3 (reviewed by reading), `timeout` and a private session for every pty script, the never-run test, ticket 8's isolation rules. One hang already happened while planning (section 0).
3. **The kernel writes into buffers** (`fcntl`/`ioctl` with a pointer argument): a too-small native buffer is memory corruption inside the JVM. Mitigation: the 1024-byte floor plus guard on every path (checklist 4), Opus on ticket 4.
4. **Polled lock waits** (1.5) differ from the kernel's queued wait (fairness, `EDEADLK`, up to 50 ms). Confined to the main thread with signals active; owner question Q3.
5. **Real modules replace silent fallbacks.** Code that caught `ImportError`/`NotImplementedError`/`KeyError` from the shells (`subprocess` pipe sizes, `shutil`/`tarfile` owner names, `posixpath.expanduser`, `getpass`, `test.support`) now runs its real path. Both interpreters then agree more often, but the full gates of ticket 9 are the first complete check; tickets 2-7 each list the users they checked by hand.
6. **`resource` is real**: user code and CPython tests can now lower limits of the JVM process (1.7). Documented; no guard, by the no-placeholders rule.
7. **Reference-build surface** (U3): the constant counts are baked into the cases (`234`, sum check). A change of reference build changes both sides at once, so cases keep passing while the generated tables go stale; ticket 0's generator rerun is the detector.
8. **Shared code**: `ImportSupport` (five arms and one set), `PosixOsProc.install` (one line), `PosixProc` (back-off helper extraction), `StubModules`. Windows is covered by W-min at every ticket and the class-loading test.
9. **NSS variety** (U7): only `files systemd` was exercised. An NSS module that misbehaves inside a JVM (its own threads, signal handlers) would show up as a crash in `pwd`/`grp` on another host; recorded as untested.
10. **Cold caches**: the first case per lane and cache costs 8-13 minutes; each ticket pays it once per lane.

## 7. Notes filled in by tickets 0 and 1
(empty: ticket 0 adds the rerun diff and, if used, the glibc 2.35 results; ticket 1 adds the probe summary and the answers to U1.)

## 8. Questions for the owner
- Q1. **Scope of the `os` terminal functions.** This plan adds `os.openpty`, `ttyname`, `posix_openpt`, `grantpt`, `unlockpt`, `ptsname` (needed to test `termios` at all, and for `pty.openpty` and later `_curses`), and leaves `os.login_tty`, `forkpty`, `tcgetpgrp`, `tcsetpgrp`, `ctermid`, `getlogin`, `getgroups`, `getgrouplist`, `setsid` absent. Recommended: as planned. Adding the read-only ones (`ctermid`, `getlogin`, `getgroups`, `getgrouplist`, `tcgetpgrp`) would be one more small sonnet ticket.
- Q2. **N0 ticket 14 (remove `PYJVM_POSIX_FDS=nio`) before or after N2?** It is released but not done. This plan assumes "after": the `fcntl`/`pwd`/`grp`/`resource` shells stay for `nio` only, with their made-up return values turned into NotImplementedError. If ticket 14 goes first, N2 deletes the four shells instead and loses the `nio` pytest lines; nothing else changes.
- Q3. **Blocking locks on the main thread are polled** (1.5) so that Python signal handlers keep running within 50 ms, at the cost of the kernel's wait queue and `EDEADLK` detection. Alternative: one blocking call (exact kernel semantics; a handler, including Ctrl-C, waits until the lock is granted). Recommended: polling, the same trade N0 and N1 made for reads and waits. Choosing the alternative removes `lockWait`, the `PosixProc` helper extraction and pytest (b), (c) of ticket 4.
