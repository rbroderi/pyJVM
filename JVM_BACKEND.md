# Modern classfile backend

Generated classes and the support runtime target **Java 25 / classfile major
69 only**. The compiler writes classes and `StackMapTable` directly in Python;
it does not use a JVM postprocessor or javac to regenerate user code.

```bash
python -m pyjvm315.cli examples/hello.py -o build/jvm -c Hello
java -Xverify:all -cp build/jvm Hello
javap -v -cp build/jvm Hello
python -m pyjvm315.conformance tests/conformance --python python3.15
```

`compile_source`, `compile_file`, `Compiler` and `ClassFile` retain `target=25`
for explicit validation; all older targets are rejected. The target propagates
to every imported module. Runtime compilation uses `javac --release 25`.
These are non-preview classfiles (minor 0); Java 25 or newer is required.
Legacy verifier-inference serialization is removed, so every method goes through
frame analysis, including straight-line methods with an empty frame table.

## Frame analysis

`pyjvm315/stackmap.py` decodes the final instruction stream after label fixups
and computes a fixed point over normal branches/fallthrough and exception-table
edges. The initial frame comes from the descriptor and static/instance access;
constructors start with `uninitializedThis`. Values track verifier types:
Object references and arrays, null, int-valued boolean results, top/unassigned
locals, and allocation-site uninitialized objects. Constructor invocation
replaces all aliases of the initialized receiver. Long/double descriptors have
category-2 widths, though primitive arithmetic/local opcodes are not emitted
or supported by this tranche.

Joins preserve matching types, merge null into a reference, conservatively
merge distinct reference classes to Object, and make incompatible/unassigned
locals top. Stack-height or incompatible operand-stack joins are errors.
Typed reference uses that need precision must supply an explicit cast; no
external class hierarchy is loaded to infer common ancestors.

Handlers get the protected instruction's **incoming locals** and one exception
value, independent of the incoming operand stack. Catch-all handlers receive
Throwable. Branch targets and reachable handlers receive full frames with
correct offset deltas and trailing-top trimming. The implicit entry frame is
not duplicated. Constants/classes for frames enter the constant pool **before**
it is serialized. Empty tables are emitted for straight-line modern methods.

Dead regions left after return/throw/goto are rewritten at identical byte
offsets to nop padding ending in athrow, with synthetic Throwable frames.
Their ranges are excluded from exception tables so synthetic locals cannot
pollute reachable handlers. Live offsets and branches stay unchanged; only
unreachable instructions are replaced. Full-frame encoding is deliberately
simple initially; compressed frame encodings can follow independently.

Modern methods derive `max_stack` from their dataflow rather than retaining the
old blanket 128. Local accesses beyond slot 255 use JVM `wide`, and local/code
size bounds fail explicitly. Unsupported opcodes, malformed branch/handler
boundaries, truncated instructions, stack underflows and reads of top locals
fail with a `FrameError`. This analyzer supports the backend's emitted subset;
it is not a complete arbitrary-JVM-bytecode verifier or a Python type prover.
New opcodes must have transfer rules before modern emission can use them.

## Validation and next steps

The conformance runner always executes Java with `-Xverify:all`. Compatibility
CI runs the full focused corpus on target 25. Backend verification never
changes Python semantic lowering, nested/local-class support, or runtime object
representation. Tests also load classes with branches/joins, nulls, dead blocks,
exception handlers, wide locals and newly initialized objects under strict
verification, and check target propagation to imported modules.

The existing benchmark harness needs no special path: it compiles with the new
default and compares against its base compiler using the same workloads. Build
and verification cost remain outside kernel timing. Generated and runtime
classfiles both use major 69, regardless of the host JDK version.

Next backend work should extend the typed instruction model before adding
primitive locals/arithmetic, then support long/double constants, branch
widening and compressed frames. Modern targets and valid frames provide the
foundation; they are not themselves a claim of near-Java speed.

The encoding and verifier rules follow the Java Virtual Machine Specification,
sections 4.1, 4.7.4 and 4.10:
https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html

## Bytecode introspection (`_opcode`, `opcode`, `dis`)

`_opcode` is a native module carrying the per-version CPython opcode tables (validity, `has_*` flags, stack effects,
intrinsic, binary-operator and special-method names), so `opcode`, `dis` and their pure-Python data work and agree
with CPython 3.13 / 3.15 (`dis.opname`, `dis.stack_effect`, `dis.hasjump`, ...). pyJVM compiles Python to JVM
bytecode and keeps no CPython bytecode, so everything that disassembles a function or code object is unsupported:
`dis.dis(func)`, `dis.dis("source")`, `dis.get_instructions`, `dis.Bytecode`, `dis.code_info` and `dis.show_code`
raise `NotImplementedError` ("pyJVM compiles Python to JVM bytecode and has no CPython bytecode ..."), as does
reading `co_code` or calling `co_lines()` on a code object. The one exception is `co_positions()` on the code objects
that traceback frames expose (`tb.tb_frame.f_code`): it is derived from the same caret table the traceback printer uses
(one position per line/column span, `tb_lasti` is an index into that table), which is enough for `traceback` to print
carets on both lanes; on any other code object it raises the same `NotImplementedError`. `_opcode.get_executor` returns
`None` (no executors) and `ENABLE_SPECIALIZATION` is 0.

Known limitation: `class C(types.ModuleType)` is accepted (so `namedtuple`/`dataclasses`/`typing` machinery that names it
as a base imports), but its instances are plain objects, not real modules: they have no `__dict__`-backed namespace
that `exec(code, module.__dict__)` or `sys.modules` insertion would treat as a module.

## Tokenizing (`_tokenize`, `tokenize`)

`_tokenize.TokenizerIter(readline, *, extra_tokens, encoding=None)` is native (`TokenizeModule`) and wraps the parser
package's `Tokenizer`, the lexer the self-hosted parser uses, so `tokenize.py` (and with it `linecache`, `traceback`,
`inspect`) imports and `tokenize.generate_tokens` / `tokenize.tokenize` / `untokenize` produce CPython's token stream:
types numbered as in the emulated version's `token.py`, positions, and the physical `line` of every token.

- `readline` is called lazily, one line each time the queued tokens run out, as CPython does: generators, interactive
  sources, `StopIteration` (end of input) and exceptions from `readline` behave the same, and the tokens of a line are
  yielded before the next line is requested. `Tokenizer.Stream` re-scans the logical line being read (tokens before the
  unfinished construct are stable, so only new ones are queued) and carries the indentation stacks between logical lines;
  inside brackets only the nesting is carried, so long literals are not re-scanned. A last line without a terminator gets
  the implicit newline of the C tokenizer (its NEWLINE/NL token has an empty string).
- The C tokenizer's lexical errors are diagnosed in this mode only (the parser keeps its lenient lexing and its own
  messages), after the tokens before them, with CPython's message and offset: `TabError` (inconsistent use of tabs and
  spaces, by comparing the column with tab size 8 and with tab size 1) and `IndentationError` for a bad dedent (offset =
  length of the line including its terminator; no DEDENT is yielded before the error; `end_lineno`/`end_offset` are
  `None`); malformed numbers (`0x`, `0x_`, `1_`, `1__0`, `0b2`, `0o8`, `1e+`, `1.e+`, `1_000_`; with
  `extra_tokens=False` also `1abc` and leading zeros, as the C tokenizer only relaxes those for tokenize); a character after
  a line-continuation backslash; EOF after a continuation or inside brackets or an f-string replacement field (`unexpected
  EOF in multi-line statement`); unterminated strings and f-strings (`... (detected at line N)`, positioned at the start of
  the string); NUL bytes. Every non-ASCII character is an identifier character, as in the C tokenizer.
  `tokenize.py` turns these into `TokenError` (or re-raises the `IndentationError`/`TabError`). Unmatched closing
  brackets are not an error here (neither in CPython's tokenize mode).
- `extra_tokens=False` (the parser's own token view, reached only through `python -m tokenize -e` and CPython's tests) is
  derived from the same stream: exact operator types, no COMMENT/NL, empty NEWLINE/INDENT/DEDENT strings with CPython's
  `-1` columns; the end-of-file tokens take the last line's position with an empty `line` after skipped trailing
  comments or blank lines, and an empty source yields nothing. Verified against CPython on ~40 stdlib files and edge cases
  (`tests/conformance/tokenize_stress.py`, `tokenize_errors.py`). Type comments (only produced when the parser asks for
  them) are not reproduced.
- Not reproduced: the `DeprecationWarning` for `1if`-style literals; the exact offset of a stray backslash or EOF after a
  continuation inside a multi-line bracket group (offsets are counted from the start of the logical line, as readline-driven
  CPython does for plain continuations); a `readline` that returns several lines at once is tokenized as one chunk.
- Under the 3.13 lane `t"..."` lexes as NAME + STRING, from 3.14 on as TSTRING tokens.

## Stdlib import-failure triage (status pipeline findings)

Modules that were rejected by the compiler, or compiled but failed to import, were grouped by error signature and
reproduced with minimal programs against CPython. Each fixed signature has a differential case in `tests/conformance/`.
Counts are modules affected in the manifest probe (approximate where one cause hid behind another).

| Signature | Modules | Classification | Status |
| --- | --- | --- | --- |
| `__py_nested_0___new__() missing ... 'name'` (email.mime.*, email.policy, ...) | 9 | runtime: `__new__` looked up on the metaclass before the MRO / `object.__new__` fallback | fixed (`object_new_with_metaclass_new`) |
| compiler rejects relative `from . import x` in a top-level module (importlib.metadata) | 1 | compiler bug: unresolvable parent decided at compile time | fixed, resolved when the import runs |
| compiler rejects module function named `run` (imaplib) | 1 | compiler bug: clashes with the exec entry method | fixed (`module_function_named_run`) |
| `CONSTANT_Utf8` > 65535 (pydoc_data.topics) | 1 | compiler/classfile: long string literal | fixed (`long_string_literal`, chunked `concat`) |
| `KeyError: '__main__'` (enum.global_enum, pickle, doctest) | ~6 | runtime: running program not in `sys.modules` | fixed (`main_module_in_sys_modules`) |
| `TypeError` iterating `__slots__` from a dict (tarfile) | 1 | runtime: `__slots__` must accept any iterable | fixed (`slots_from_dict`) |
| `type.__dict__['__mro__']`, class `__doc__`, enum member `__init__` | ~4 | runtime gaps | fixed (`class_doc_and_type_descriptors`) |
| `T.__repr__` missing/unequal on builtin types (pprint, unittest) | 2 | runtime | fixed (`builtin_type_repr_attribute`) |
| dict view `<=` against sets (pickletools) | 1 | runtime | fixed (`dict_view_set_ordering`) |
| `math.sumprod` missing | 1 | runtime gap | fixed (`math_sumprod`) |
| `os.terminal_size` missing (shutil) | 1 | runtime gap | fixed (`os_terminal_size`) |
| `_sysconfigdata__linux_*` missing (zoneinfo) | 2 | runtime gap (stub module) | fixed |
| `time.struct_time`, `gmtime/localtime/mktime/asctime/ctime/strftime/strptime` (calendar, `_strptime`, mailbox) | 3 | runtime gap | fixed (`time_struct_time_and_strftime`) |
| wrong-length unpack raised `TypeError` instead of `ValueError` (email `_get_params_preserve`) | all of email | runtime bug | fixed (`unpack_value_error`) |
| classes built with a custom `__prepare__` mapping lost `__module__` (enum members, `calendar.MONDAY`) | ~3 | runtime bug | fixed (`class_module_with_prepare`) |
| `object.__init__` rejected extra args when called as a bound method (3.15 enum `__set_name__`) | ~3 | runtime bug | fixed (`class_doc_and_type_descriptors`) |
| `sys.abiflags`, `sys.copyright`, `getrecursionlimit` | several | stale manifest entries (already present) | not a bug |
| `sitecustomize`, `site` | 2 | harness artifact (sandbox sitecustomize needs `_imp.extension_suffixes()`) | not fixed |
| missing native modules (`_socket`, `_typing`, `_ctypes`, `_posixsubprocess`, `_curses`, `_sqlite3`, `_codecs_*`) | many | unimplemented native modules (out of scope) | not fixed; `pprint`, `inspect`, `unittest` still stop at `_typing` on the 3.13 lane |
| `encodings.utf_7` codec | 1 | runtime gap (no utf-7 codec) | not fixed |
| dict view types are not named `dict_keys`/`dict_values`/`dict_items`; `str()` of an `IntEnum` member after `global_enum` | small | runtime divergences | not fixed |

## sys: recursion limit, audit events, bytecode caching

**Recursion limit.** Like CPython 3.12+ only Python frames count (per thread, module frame included): entering the
(limit+1)th active Python frame (function, method, generator resume, class body, module body) raises
`RecursionError: maximum recursion depth exceeded`, which is the depth CPython 3.13 and 3.15 stop at (a recursive
function started from the module reaches `limit - 2` calls, `tests/conformance/recursion_limit_depth.py`).
`sys.setrecursionlimit(n)` raises `RecursionError` when `n` is not above the current depth, as CPython does. Remaining
differences: CPython has a second, C-level recursion limit (repr/compare/pickle of deeply nested data) that raises at a
fixed depth; pyJVM has none and relies on the JVM stack, so such recursion ends in a `RecursionError` at a depth set by
`-Xss`, and a recursion limit above what the JVM stack can hold ends in a `StackOverflowError` that is turned into a
`RecursionError` before the limit is reached. importlib runs from source here, so its
frames count as in CPython, but CPython's frozen importlib adds its own frames in a slightly different number.

**Audit events (PEP 578).** `sys.addaudithook` / `sys.audit` work as in CPython (hooks cannot be removed, a hook that
raises vetoes the event, argument errors match). The runtime raises these events with CPython's argument shapes
(verified against 3.13 and 3.15 in `tests/conformance/audit_events.py`): `open` (`open()`, `io.open`, `FileIO`, `os.open`;
flags in the platform's O_* values), `compile`, `exec` (`exec`, `eval`, and every module body run by the import system),
`import` (not-yet-imported module, its parents and from-list submodules, with `sys.path`, `sys.meta_path`,
`sys.path_hooks`), `os.listdir`, `os.scandir`, `os.chdir`, `os.mkdir`, `os.rmdir`, `os.remove`, `os.rename` (also
`replace`), `os.chmod`, `os.utime`, `os.link`, `os.symlink`, `os.kill`, `sys._getframe`, `sys._current_frames`,
`sys._current_exceptions`, `sys._getframemodulename`, `sys.settrace`, `sys.setprofile`, `sys.addaudithook`,
`time.sleep`, `builtins.input` and `builtins.input/result`. Events the pure-Python stdlib raises on its own come for free
(`os.walk`, `shutil.*`, `tempfile.mkdtemp`, `glob.glob`). Not raised, because the operation is absent or a no-op here:
`os.putenv`/`os.unsetenv` (placeholders), `marshal.*` (not implemented), `socket.*`, `subprocess.Popen`/`os.system`/`os.exec*`
/`os.fork`, `ctypes.*`, `pickle.find_class`, `gc.*`, `builtins.id`, `object.__getattr__`/`__setattr__` on code and
functions, `code.__new__`/`function.__new__`, `_thread.start_joinable_thread`. The runtime's own bootstrap (compiling
importlib's sources, `_typing`, the jar finder) raises no events, as CPython's frozen importlib raises none.

**Bytecode caching: `sys.implementation.cache_tag` is `None` on purpose.** importlib uses `cache_tag` to build `.pyc`
names (`importlib.util.cache_from_source`, `py_compile`, `compileall`, `SourceFileLoader`'s bytecode writes) and treats
`None` as "this implementation does not cache bytecode", which is legal (`cache_from_source` then raises
`NotImplementedError`, and the source loader skips `.pyc` reads and writes). A real tag would be a promise that a
`.pyc` in CPython's marshal format, holding a code object CPython can execute, is read and written; pyJVM compiles to
JVM class files, has no code-object bytecode and its `marshal` cannot encode code objects, so such files could be
neither produced nor consumed, and a tag such as `pyjvm-313` would only make importlib try (and fail) to write them. The
compiled-class cache (`PYJVM_CACHE`, `ClassCache`, `tests/test_class_cache.py`) does the job of `.pyc` files and is keyed
by the source and the compiler fingerprint instead, so `cache_tag` stays `None`, `sys.dont_write_bytecode` is only
reported.

## Finalizers and reference-counting timing

`__del__` runs through JVM finalization (`GcFinalizable`, delivered on the creating thread by `WeakrefDelivery`), so it
runs after a collection finds the object unreachable, not when the last reference goes away as in CPython. Finalizers
also do not run at interpreter exit (CPython finalizes module globals at shutdown). `gc.collect()` forces a collection
and runs the pending finalizers; the JVM has no way to collect selected objects.

One stdlib path depends on the immediate timing: `subprocess` on Windows makes each pipe end inheritable with
`DuplicateHandle` and drops the original `Handle`, whose `__del__` closes it. If the original write end stays open, the
parent's reader never sees EOF and `communicate()` waits forever. `_winapi` therefore tracks the ends `CreatePipe`
returned and which of them were the source of an inheritable duplicate; while such an original is still open,
`_winapi.CreateProcess` runs a full collection (with finalizers) before the child starts, which leaves the process in
the state CPython is in at that point. Closing a handle by any route (`CloseHandle`, `_close` of an `open_osfhandle`
descriptor, `DUPLICATE_CLOSE_SOURCE`) clears its entry, so spawns without pipes do not collect.

On POSIX the same timing has a visible consequence for pipes. A `FileIO` or file object that owns the write end of an
`os.pipe()` and is merely unreachable still holds the real descriptor until a collection runs its `__del__`, so a reader
of the other end does not see EOF in the meantime (CPython closes it at the last reference). `gc.collect()` runs the
pending finalizers and closes it; code that needs EOF at a point should close the write end explicitly. The late close
is guarded against reused descriptor numbers (see "POSIX native layer"). Child processes are not covered yet: `subprocess`
on POSIX arrives with N1, which must check this timing for its pipes the way `_winapi` does above.

## POSIX native layer (Linux x86-64, glibc)

On Linux, descriptors are the kernel's. The number `os.open`, `os.pipe`, `os.dup` and `os.dup2` return is the real
descriptor number, and there is no Java-side table behind it: access mode, `O_APPEND`, offsets, close-on-exec and
non-blocking mode live in the kernel, and `os.fstat(fd)` reports what the kernel reports. The classes are `PosixNative`
(FFM plumbing, errno, host check), `PosixFds` (descriptor system calls), `PosixOsFds` (the `os` functions that exist only
with kernel descriptors: `pipe`, `pipe2`, `dup2`, `pread`, `pwrite`, `closerange`, `readinto`, `get/set_inheritable`,
`get/set_blocking`, `stat`/`lstat`, ids, `umask`, the extra `O_*`/`SEEK_*` constants), `PosixSelect` and
`PosixSelectModule` (`select`, `poll`, `epoll`) and `PosixErrnoText` (the generated `strerror` table, errno 0-133, so
messages stay English whatever locale the JVM launcher set). They are never initialised on Windows. The plan, the
measurements and the per-ticket results are in `work/n0_plan.md`, `work/n0/` and `HANDOFF.md`.

`IoFd` keeps its old static API and dispatches to `PosixFds` when `IoFd.KERNEL` is true. `KERNEL` is true on POSIX unless
the environment variable `PYJVM_POSIX_FDS` is `nio` (the rollback to the older `FileChannel` table; Windows never reads
the variable). In `nio` mode the functions that need real descriptors are absent (`hasattr(os, "pipe")` is false), as
before. The host (Linux x86-64 glibc) is checked once, when the kernel layer is first used. Path based calls other than
`os.stat`/`lstat`/`DirEntry.stat` (`listdir`, `rename`, `mkdir`, ...) still go through java.nio.

**FFM conventions.** Every handle is built by `PosixNative.fn` and captures `errno` (`captureCallState("errno")`); only
functions that cannot fail (`malloc`, `free`, `getuid`, `umask`, ...) do not. Variadic functions (`open`, `fcntl`) use
`firstVariadicArg`. `errno` is read only after a call has failed, because a successful call leaves the previous value in
the call-state segment. Each thread has one call-state segment and one 64 KiB buffer (grown up to 1 MiB; larger
transfers use `malloc` so that a big `read` is one `read(2)`); no `Linker.Option.critical`, since these calls block.
Descriptor arguments that do not fit a C `int` raise `OverflowError`; negative numbers go to the kernel and come back as
`EBADF`. Paths are encoded as UTF-8 with `surrogateescape`; error messages carry the string the caller passed.

**Descriptors.** Every descriptor the runtime creates is created close-on-exec atomically (`O_CLOEXEC`,
`pipe2(O_CLOEXEC)`, `F_DUPFD_CLOEXEC`, `dup3`, `EPOLL_CLOEXEC`). Only what Python asks for is inheritable:
`os.dup2(..., inheritable=True)` (the default, as in CPython), `os.pipe2(flags)` without `O_CLOEXEC`,
`os.set_inheritable(fd, True)`. Descriptors 0-2 are ordinary: `os.close(1)` closes the real descriptor 1. `close`,
`dup2` and `dup3` are never retried on `EINTR`. A write to a pipe whose reader is gone raises `BrokenPipeError`; the JVM
survives `SIGPIPE`.

**Descriptor lifetime and the generation guard.** `_io` objects close their descriptor from `__del__`, which runs after a
collection, later than in CPython (see "Finalizers and reference-counting timing"). With real descriptors that late close
could hit a number that was closed and reused meanwhile. `PosixFds` therefore keeps a generation per descriptor number it
handed out: `adopt(fd)` after every successful descriptor-creating call (`open`, `dup`, `pipe`/`pipe2`, `epoll_create1`, the target of
`dup2`) stores a fresh generation, `forget(fd)` runs before `close(2)`, and `gen(fd)` is 0 for an unknown number. A
`FileIO` records the generation when it is initialised; a close reached from a finalizer (`GcFinalizable.inDel()`) that
finds a different generation skips the `close(2)`. An explicit close always closes. The map is not a descriptor table;
nothing reads it for I/O. `select.epoll` objects close their descriptor from a cleaner under the same rule. Limits (found
in review, not fixed): only `close(2)` is guarded, so a buffered writer dropped after its descriptor was closed and
reused flushes its pending bytes into the new descriptor when it is finalized; a number that was never adopted
(generation 0: inherited, or opened by the JVM) is not guarded; after `os.dup2(g, f.fileno())` a finalizer-driven close
of `f` leaves the target open. Code that creates descriptors (the N1 and N3 phases) must call `PosixFds.adopt`, or its
descriptors are unguarded.

**`os.closerange` and JVM-owned descriptors.** The JVM holds descriptors of its own (class path jars and others it
opened), and a shell may pass more down. `os.closerange(low, high)` closes the numbers 0-2 and the numbers pyJVM
adopted; it skips numbers above 2 that were never adopted, so JVM-internal and inherited descriptors survive
`os.closerange(3, 1024)` (CPython would close them; its interpreter holds almost none, and closing the JVM's own
descriptors crashed the JVM in a test). It walks 0-2 and the generation table, never the int range or `/proc`; above 2 a number is closed only if this call removed it from the table. An explicit
`os.close(fd)` still closes any number, so a JVM-owned descriptor can still be closed by naming it, with the risk that
implies. The consequence for child processes: an inherited descriptor that is not close-on-exec survives `closerange`
and would be inherited by a spawned child, so process creation must not rely on `os.closerange` to close descriptors.

**Signals and blocking calls.** Python handlers run only on the main thread, at the points that call
`SignalModule.dispatchPending()`: before and between the slices of a blocking `read`/`write` on a descriptor, in
`select`/`poll`/`epoll` waits, in `time.sleep` and `signal.pause`, and in `signal.signal`, `raise_signal` and
`pthread_sigmask`. They do not run between bytecodes, so a pure-Python loop that does none of these never runs one. The
OS may deliver a signal to any JVM thread, so a main thread parked in `read(2)` is generally not interrupted. Once
`_signal` has been imported, a `read` or `write` on the main thread therefore first waits for readiness with `poll`: a
0 ms probe (so ready and non-blocking descriptors cost one extra `poll`), then 50 ms slices, running pending handlers
between slices. `select`, `poll` and `epoll` waits on the main thread use the same slices; other threads make one call
with the full timeout. `EINTR` from `open`, `read`, `write`, `pread`, `pwrite`, `fsync`, `ftruncate`, `fstat`, `select`,
`poll` and `epoll_wait` runs the pending handlers and retries (PEP 475), recomputing the remaining timeout from a
`System.nanoTime()` deadline. A handler that raises propagates out of the call before any byte was transferred. No
handler runs between a system call and the copy-out of its result, so a handler may itself use `os.read`/`os.write`.
`signal.set_wakeup_fd` works on POSIX; a failed wakeup write is reported on stderr with the lane's header. Limits:
- a handler can run up to about 50 ms late while the main thread is blocked in a read, write or wait;
- handlers run only at the points above, not at bytecode boundaries;
- a blocking `write` of more than the pipe capacity cannot be interrupted once it is inside `write(2)` (the pre-wait
  only guarantees the pipe is writable when the write starts), and an `EINTR` retry does not repeat the pre-wait;
- other threads never slice;
- an inherited `SIGINT` set to `SIG_IGN` (a background job of a non-interactive shell) is kept, as in CPython; in that
  case a later `signal.signal(SIGINT, handler)` updates `getsignal` but HotSpot never installs an OS handler, so a real
  Ctrl-C or an outside `kill -INT` does not run the handler (signals raised inside the process, such as
  `os.kill(os.getpid(), ...)` and `raise_signal`, do). The `nio` rollback installs `default_int_handler` over the
  inherited `SIG_IGN`;
- there is no `fork`; processes arrive with N1 (`posix_spawn`).

**Non-blocking descriptors and re-entrancy in `_io`.** With real descriptors `os.set_blocking(fd, False)` and
`O_NONBLOCK` work: `FileIO` and the buffered classes return `None` or raise `BlockingIOError` (with
`characters_written`) as CPython does (case `io_nonblocking`). A buffered object's methods hold a lock like CPython's
`ENTER_BUFFERED`: a signal handler that calls into the same buffered object on the same thread while it is inside a
blocking read or write gets `RuntimeError: reentrant call inside <...>`, and another thread waits. This is in the shared
`_io` code, so Windows has it too. `TextIOWrapper.read()` on an empty non-blocking pipe raises the 3.13 `TypeError` on
every lane (3.14+ raises `BlockingIOError`): a known gap.

**Emulated working directory.** The JVM cannot `chdir(2)` (its own relative class-path entries would break), so
`os.chdir` sets `PosixModule.cwd` and relative paths are resolved by string join (`cwd + "/" + path`, no
normalisation, so `..` is resolved by the kernel through symlinks). The process's real working directory does not
change. Anything that hands a relative path or the working directory to the kernel or to a child process must account
for this; `posix_spawn` (N1) has to add a chdir file action for the emulated directory whenever it is set.

## Windows overlapped I/O (`_overlapped`, `_socket`, asyncio's ProactorEventLoop)

`WindowsIocp` (kernel32) and `WindowsSockets` (ws2_32) are Foreign Function and Memory API layers; every call captures
`GetLastError`/`WSAGetLastError` per call. `_overlapped.Overlapped` objects own calloc'd memory for the OVERLAPPED and
the I/O buffer, because the kernel keeps both until the operation ends and the JVM has no destructor point. A
`Cleaner` frees them when the Python object is collected. CPython raises "still has pending operation at deallocation"
there; pyJVM does not. A pending operation is instead cancelled and its state parked as an orphan, kept until the
operation has ended and, for handles associated with a completion port, until its completion packet has been dequeued
by `GetQueuedCompletionStatus` (the kernel writes the final status of such an operation only then, so `pending` stays
true until a dequeue; this is Windows behaviour, CPython sees it too). Memory is never freed while the kernel owns it.
`ReadFile`/`ReadFileInto`/`WSARecv`/`WSARecvInto` read into native memory; `ReadFileInto` and `WSARecvInto` copy it
into the caller's buffer once, on the first successful `getresult`, so that data is not visible in the buffer earlier.

`RegisterWaitWithQueue` uses `RegisterWaitForSingleObject` with one global upcall stub that only posts the completion
packet. `GetQueuedCompletionStatus(INFINITE)` blocks in native code and cannot be interrupted; Ctrl-C reaches Python
through `signal.set_wakeup_fd` (a socket, as on CPython): the delivered signal writes one byte to it, the
`WSARecv` the loop keeps on its self-pipe completes, and `GetQueuedCompletionStatus` runs the pending Python signal
handlers (a safe point) before returning. `select.select` on Windows runs in 50 ms slices for the same reason.
`_socket` is a minimal stream-socket subset; host names in addresses go through the JVM resolver, and datagram,
`AcceptEx`/`ConnectEx` and `getaddrinfo` are absent (asyncio TCP/UDP servers and clients do not work yet).

## unittest and CPython's test modules

`import unittest` works under pyJVM and a `TestCase` suite runs end to end: `unittest.main()`, `python -m unittest`,
discovery, `TextTestRunner` output and tracebacks, `assertRaises`/`assertRaisesRegex`, `subTest`, `skip*`,
`expectedFailure`, `setUp`/`tearDown`/`setUpClass`. The differential cases are `tests/conformance/unittest_basic.py`,
`unittest_subtests.py` and `support_helpers.py` (all pass on the 3.15 lane; `support_helpers` needs CPython's `Lib/test`
and prints `test.support unavailable` without it). `test.support` imports and its common helpers work
(`import_helper`, `os_helper` incl. `temp_dir`/`EnvironmentVarGuard`, `warnings_helper`, `swap_attr`, `captured_stdout`,
`cpython_only` - skipped, because `sys.implementation.name` is not `cpython` - and `check_impl_detail`).

Supporting runtime work: `time.struct_time`, `gmtime`/`localtime`/`mktime`/`strftime`/`asctime`/`ctime` (and
`strptime` through `_strptime`), `os.scandir` as a context-manager iterator, `sys.modules['__main__']`, assignable
`tb_next`, `f_globals` of traceback frames, exception matching by class identity (same-named classes in different
modules no longer clash), importable shells of `_posixsubprocess`, `fcntl`, `pwd`, `grp` (every operation raises), and
the wait-status helpers of `os`.

### Running a CPython test module

```
PYJVM_CPYTHON_LIB=/path/to/cpython/Lib \
PYJVM_CACHE=/tmp/pc PYJVM_BUILD_CACHE=/tmp/bc \
python3.15 tools/run_cpython_test.py [-v] [-k PATTERN] [--jvm-only|--cpython-only] test_bool test_tuple ...
```

`libregrtest` itself is not used (it needs `faulthandler`, subprocess workers and `signal` features pyJVM lacks).
`tools/pyjvm_regrtest.py` is the in-process runner: it imports `test.<name>`, runs its unittest suite and prints one
`RESULT <test id> <ok|FAIL|ERROR|skipped|expected failure|unexpected success>` line per test. `run_cpython_test.py`
runs it under CPython and under pyJVM (self-hosted build, `RunTest` main class) and prints every test whose status
differs plus `N tests match, M differ`. The corpus defaults to `$PYJVM_CPYTHON_LIB`.

Current state (3.15 lane): across `test_bool`, `test_tuple`, `test_sort`, `test_copy` and `test_struct` 141 of 220 tests have the same status as under CPython. Starting a test module costs minutes the first
time (stdlib modules are compiled at run time by the self-hosted compiler); later runs reuse `PYJVM_CACHE`.

### Known gaps

- `unittest.mock` imports and the basics work (`Mock`/`MagicMock`, `call` assertions, `side_effect`, `patch`, `patch.object`,
  `patch.dict`, `Mock(spec=...)`; `tests/conformance/unittest_mock_basic.py`). Not working: `create_autospec`
  (`delattr` of a magic method on the mock type) and configuring a magic method through `mm.__getitem__.return_value`.
- `_typing` is built from embedded Python source (`TypingSource.java`) and shares the runtime's own `Generic` (the
  implicit base of PEP 695 classes), so the pure-Python `typing` imports on the 3.13 and 3.15 lanes. `_socket`, `fcntl`,
  `pwd`, `grp`, `resource` and `_posixsubprocess` are import-only shells.
- No `faulthandler`, real sockets, real `fcntl`/`pwd`/`grp`, no subprocess spawning (`_posixsubprocess.fork_exec`,
  `os.waitpid` raise), so tests that spawn interpreters (`script_helper`) cannot run.
- Test modules that depend on CPython internals (`sys.getrefcount`, `gc` tracking, `_testcapi`, bytecode, `marshal` of
  code objects) report those tests as errors or skips; the differing ones are listed by `run_cpython_test.py`.
- `f_globals` of a live frame is the live module dict (`f.f_globals is globals()`). Traceback entries are separate frame
  objects, so `tb.tb_frame is sys._getframe()` is still False, and frames have no `f_builtins`.
