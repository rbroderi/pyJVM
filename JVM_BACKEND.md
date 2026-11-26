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
- there is no `fork`; processes are created with `posix_spawn` (see "Processes" below).

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
for this; every `posix_spawn` of N1 adds a chdir file action for the emulated directory whenever it is set (see "Processes").

### Processes (N1: `posix_spawn`, the wait family, `kill`, `subprocess`)

Everything in this subsection exists only with kernel descriptors on POSIX (`IoFd.KERNEL`; Linux x86-64, glibc 2.34 or
later, checked when `PosixSpawn` is first used). Under `PYJVM_POSIX_FDS=nio` the old state remains: `_posixsubprocess`
is the importable shell of `StubModules` and `os.waitpid`/`os.wait` raise `ChildProcessError`. Windows never loads these
classes (`tests/test_import_runtime.py::test_windows_never_loads_posix_ffm`); its `subprocess` is
`_winapi.CreateProcess`. The classes: `PosixProc` (FFM for `waitpid`, `waitid`, `wait4`, `kill`, `killpg`, `getpgid`,
`getsid`, `setpgid`, `sysconf`, `confstr`, `pidfd_open` through `syscall(434)`, `faccessat`; the blocking-wait rule),
`PosixSpawn` (the spawn core), `PosixOsProc` (the `os` functions, installed over the shared ones by the last line of
`PosixOsFds.install`), `PosixSubprocessModule` (`_posixsubprocess`, one name: `fork_exec`), `PosixConfNames` (the
generated `sysconf_names`/`confstr_names`, 134 and 27 names) and `PosixStructSeq` (a tuple-backed structseq factory,
used for `os.waitid_result` and `resource.struct_rusage`). Plan and measurements: `work/n1_plan.md`, `work/n1/`; the
CPython test triage: `work/n1/cpython_tests.md`.

**`posix_spawn`, never `fork`.** The JVM cannot fork safely, so a process is always created by `posix_spawn[p]` through
FFM, from one confined `Arena` per call. `os.fork`, `forkpty`, `execv*`, `os.spawn*`/`P_WAIT` (os.py defines them only
where `fork` exists) and `register_at_fork` are absent. Also absent (not N1; `hasattr` is false): `abort`, `setsid`,
`setpgrp`, `tcgetpgrp`, `tcsetpgrp`, `times`, `nice`, `getpriority`, `setpriority`, `sched_*`, `getlogin`, `uname`,
`ctermid`, `getgroups`, `getres[ug]id`, `set*id`, `setgroups`, `initgroups`, `getloadavg`, `unshare`, `setns`.
Present: `posix_spawn` and `posix_spawnp` (with `POSIX_SPAWN_OPEN/CLOSE/DUP2`, no `POSIX_SPAWN_CLOSEFROM`, and
`setsid=True` raising `NotImplementedError`, as the reference CPython builds do; a `scheduler` tuple raises
`NotImplementedError`), `waitpid`, `wait`, `wait3`, `wait4`, `waitid` (`P_PID`, `P_PGID`, `P_ALL`, `P_PIDFD`),
`waitstatus_to_exitcode` and the `W*` macros, `kill`, `killpg`, `getpgid`, `getpgrp`, `getsid`, `setpgid`, `sysconf`,
`confstr` (`SC_EQUIV_CLASS_MAX` raises `OSError(22)` because libc does, and `sysconf_names`/`confstr_names` are read at call time; both as in CPython), `pidfd_open`, `system`. `os.popen` and `subprocess` are Python code on top of these and work.

**Arguments `fork_exec` cannot honour.** With no fork there is no point at which Python code or `setuid` could run in the
child. `_posixsubprocess.fork_exec` raises, before anything is created (pyJVM-specific texts, tested by pytest only):
`preexec_fn` not None -> `RuntimeError`; `uid`, `gid`, `extra_groups`, `child_umask >= 0` -> `NotImplementedError`
(`subprocess: user is not supported by pyJVM ...`). Through `subprocess`, `user=`, `group=` and `extra_groups=` never get
that far: they raise `ValueError("The 'user' parameter is not supported on the current platform")` (and likewise)
because `os.setreuid`/`setregid`/`setgroups` are absent. Lifting these needs the Rust spawn helper of the
native-modules plan.

**Child start state.** The child is what `posix_spawn` makes. `SETSIGMASK` is always set: the mask is the caller's, else
the Python-level `pthread_sigmask` set (`SignalModule.blockedMask()`), so a child inherits what Python blocked and not the
JVM thread's own mask (SIGQUIT is blocked on Java threads). `restore_signals` resets SIGPIPE and SIGXFSZ with
`SETSIGDEF`. Gaps against CPython, all from what `posix_spawn` can express:
- (a) CPython itself ignores SIGPIPE and SIGXFSZ, so its children started with `restore_signals=False` or with plain
  `os.posix_spawn` have them ignored; under pyJVM they are default (HotSpot installs handlers, not `SIG_IGN`).
- (a2) A SIGQUIT set to ignored by the JVM's launcher does not reach the children: HotSpot installs its own SIGQUIT
  handler before any Java code runs, so the ignore cannot be seen or passed on, and `posix_spawn` can only reset it to
  default (an ignored SIGHUP is inherited as usual). Gates started with `setsid nohup` therefore reset SIGHUP and
  SIGQUIT first, or `subprocess_posix`'s `SigIgn` line differs.
- (b) Children inherit the JVM's raised soft `RLIMIT_NOFILE` (HotSpot raises it to the hard limit; 1048576 measured).
- (c) The start-up order is glibc's (signals, session, group, then file actions), not CPython's (descriptors, chdir,
  signals, session, group); only which error is reported for a call that is wrong in two ways can differ.
- (d) `fork_exec` with `close_fds=True` lists the parent's descriptors before the spawn (CPython lists inside the
  child): a descriptor another thread makes inheritable in `[3, maxK)` between the listing and the spawn is not closed.

**Descriptors in children.** Inheritance is the kernel's: only descriptors that are not close-on-exec at spawn time, or
are named by a `DUP2` action, reach a child. `fork_exec` builds `CLOSE` actions for the parent's open descriptors in
`[3, maxK)` outside the kept set and ends with `CLOSEFROM(maxK + 1)` (`os.closerange` is not used for this). The JVM
holds descriptors of its own that are **not** close-on-exec (the JDK's `lib/modules` jimage, `/dev/random` and
`/dev/urandom` from NativePRNG, and a JDK directory stream while it is open). Before every spawn whose actions do not end
in `CLOSEFROM(3)` (`close_fds=False`, `os.posix_spawn[p]`, `os.system`), `PosixSpawn` lists the parent's descriptors and
adds a `CLOSE` for each `f > 2` that Python never adopted (`PosixFds.gen(f) == 0`), that is not close-on-exec, and that
points under `java.home`, at or under a class-path/module-path entry, or at `/dev/random`/`/dev/urandom`. Limits: a
descriptor the JVM inherited from its launcher that matches that rule is also closed in children (CPython would pass
it); a JDK directory stream open on another thread at the moment of a spawn without `CLOSEFROM(3)` can pass its
descriptor on; `gen(f) == 0` is re-checked when each `CLOSE` is added, so only the native calls up to `posix_spawn`
remain as a window. `fork_exec`'s temporaries and every pidfd are close-on-exec, adopted through `PosixFds` and closed
in `finally`.

**Environment and working directory.** `env=None` means the live libc `environ`, read at every spawn (never
`System.getenv()`, a start-up snapshot), so `os.putenv` is seen by children (case `os_putenv_child`).
`PosixNative.ENV_LOCK` serialises `setenv`/`unsetenv` with the `environ` read and the spawn call, because glibc's
`setenv` may free the array while the child still shares the address space. For the emulated working directory (above),
every spawn starts with a `CHDIR` file action for `PosixModule.cwd` when it is set, before any action that names a
relative path. Limit: if that directory has been removed or renamed since `os.chdir`, the chdir action fails with ENOENT
and nothing can be spawned (a CPython child would start in the unlinked directory); `os.system` then returns `127 << 8`.

**Failed starts: the error record and the stand-in child.** `subprocess` expects `fork_exec` to start a child that
reports its failure through `errpipe_write` and exits 255. After a failed `posix_spawn` no child exists, so `fork_exec`
tries each entry of `executable_list` with `posix_spawn` (CPython's errno rule: the first errno that is neither ENOENT nor
ENOTDIR, else the last), classifies the failure, writes one record to `errpipe_write` itself (`OSError:<hex errno>:`
plus a suffix: `noexec` for EBADF, for a failed stdio temporary (any errno) and for EPERM/ESRCH with a process group, `noexec:chdir` when a chdir is part of the
plan and the parent-side check of the emulated cwd and `cwd` fails; an empty list gives `SubprocessError:0:`), and then
starts a **stand-in child**, `/bin/sh -c "exit 255"` with the libc environment, an empty signal mask and `CLOSEFROM(3)`,
and returns its pid. `Popen` then collects returncode 255 with an ordinary `waitpid`, as with CPython; nothing in the
wait path is special-cased. If the stand-in cannot start, `fork_exec` returns its own pid (`waitpid` of it is `ECHILD`,
which `subprocess` handles). `errpipe_write` is never given to a child, so a successful child cannot keep `subprocess`
blocked on `errpipe_read`. The chdir diagnosis happens in the parent after the spawn, so a concurrent `os.chdir` or
directory change can make it look at a different state (accepted race). Known gaps, reachable only through direct
`fork_exec` calls: an empty `executable_list` with a bad `cwd` or bad stdin descriptor records `SubprocessError:0:`
(CPython `OSError:2:noexec:chdir` / `OSError:9:noexec`); a failing write of the error record raises and starts no child;
a bad stdio descriptor together with `pgid_to_set` reports EPERM where CPython reports EBADF; a dict subclass as
`executable_list` or `env_list` fails with `KeyError` instead of "is not a sequence".

**Waiting: the rule and its latencies.** Python handlers run only on the main thread (above), and a `waitpid(2)` parked
on it is generally not interrupted by a signal. One function serves `waitpid`, `wait`, `waitid`, `wait3` and `wait4`
(`PosixProc.await`). With `WNOHANG`, off the main thread, or before `_signal` is imported, it is a single blocking call
(`EINTR` runs the pending handlers and retries). On the main thread with signals active it loops: a `WNOHANG` call,
`dispatchPending()`, then, for one specific process with exit-only options, a `poll` on a transient pidfd
(`pidfd_open`; 50 ms slices; readable when the process has exited), else a back-off sleep doubling from 1 ms to 50 ms.
Latency: none to speak of for a single child (`os_wait_signals` anchors a mean spawn-plus-wait below 40 ms for 20
`/bin/true` children; `subprocess.run(["/bin/true"])` costs about 1.5 ms against CPython's 0.64 ms, measured in
`work/n1/cpython_tests.md`); up to `min(time waited, 50 ms)` for `os.wait()`, `waitpid(-1, ...)`, group waits and
stop/continue waits; a Python handler runs at most about 50 ms late during any wait. A handler that raises propagates out
of the wait with the child left unreaped, as in CPython. `os.system` is the exception: its handler exception is held until
the child has been reaped (CPython's `system(3)` is not interrupted by Python handlers) and only the first one is kept;
once one is pending, a later KeyboardInterrupt during a long child is dropped. `system(3)` itself is never called (it
would change the JVM's signal dispositions process-wide), so its ignoring of SIGINT/SIGQUIT in the parent and blocking of
SIGCHLD while waiting are not reproduced (a SIGINT handler runs during `os.system` here), and `os.system` uses
`sh -c -- command` with a failed spawn reported as `127 << 8`. Throughput limit: `subprocess.run(["cat"], input=64 MiB,
capture_output=True)` takes about 1.4 s against 0.22 s (the `communicate()` loop of small `poll`/read/write calls in
Python; not investigated).

**`waitpid(-1)` and the JDK's children.** The JDK reaps only children that `ProcessImpl` started, with one reaper thread
per pid, so pyJVM's own children stay waitable and the JDK never takes them. The reverse is a real hazard: `os.wait()`,
`os.waitpid(-1, ...)` and `os.waitid(P_ALL, ...)` can collect a JDK-started child (today only `ExecSupport`'s host-Python
helper, used when the self-hosted compiler is not on the class path), after which the JDK reports exit status 0 for it.
There is no guard. Rule for the runtime: no new `ProcessBuilder` use; on POSIX kernel mode `os.kill` no longer starts
`/bin/kill`.

**Signals to processes.** `os.kill(os.getpid(), sig)` keeps its in-process delivery (the Python handler has run when it
returns, as in CPython); every other target goes to libc `kill(2)`, and `killpg` to `killpg(2)`. The signal of `kill` is
truncated to its low 32 bits like CPython's. **Danger:** `os.kill(0, sig)`, a negative pid, `os.kill(-1, sig)`,
`os.killpg(0, sig)` and `os.killpg(1, sig)` (glibc turns it into `kill(-1, sig)`: every process of the user) can signal the
JVM itself or everything the user owns. Their behaviour with a real signal was deliberately never exercised: a Python
handler runs only for signals hooked through `sun.misc.Signal`, otherwise HotSpot's own disposition applies. Tests and
scripts must signal only children they created (by pid, or their own `process_group=0`/`setpgroup=0` group) and their
own pid (`work/n1_plan.md` 2.3; a measurement script's `os.killpg(1, 15)` once ended a whole WSL session).

**`sys.executable`.** It is the string `"pyjvm"`, not a runnable program, so `subprocess.run([sys.executable, ...])` and
every CPython test that starts `sys.executable` fail with `FileNotFoundError`; N1 builds no launcher (owner decision).
In `work/n1/cpython_tests.md` this blocks 175 tests across the selected CPython test classes (in `test_subprocess`,
`test_posix`, `test_os`, `test_popen` and `test_signal`; `test_subprocess`'s five classes have 220 tests, of which pyJVM
passes 53; `test_posix` imports only through a triage shim, because a class-level `skipIf` calls the
absent `os.sched_getscheduler`). With a runnable stand-in executable 173 of the 175 pass and the other two fail on `Popen.__del__` not running
at `gc_collect()` (core GC timing), so no N1 defect hides behind the label. The remaining recorded failures: `preexec_fn`
(6 tests), `umask` (1) and `restore_signals` (1, gap a above), the `os.P_NOWAIT`/`os.spawnv` tests (no fork), one
loopback-socket test (N3), and four issues in core code outside N1.

**Other recorded limits.**
- `PosixStructSeq` instances (`os.waitid_result`, `resource.struct_rusage`, `pwd.struct_passwd`, `grp.struct_group`) can
  be constructed from Python, pickled and copied since N2 ticket 1; the remaining gaps are in the N2 subsection below.
- `wait3(foo=1)` and `wait4(-1, pid=1)` give other `TypeError` texts than CPython (shared `NativeArgs`); a pid-like
  argument that is an `__index__` object beyond 63 bits gives a `TypeError` where CPython raises `OverflowError`
  (`PosixFiles.fd`).
- The audit event of `os.kill` is raised before its argument checks (the `AuditEvents` wrapper).
- An `ENOMEM` while adding a file action to `os.posix_spawn`'s action list surfaces after the `os.posix_spawn` audit
  event (CPython fails before it).

### Small libc modules (N2: fcntl, termios, resource, pwd, grp, ptys)

With kernel descriptors (the default on Linux) `fcntl`, `termios`, `resource`, `pwd` and `grp` are real, and `os` gains
`openpty`, `ttyname`, `posix_openpt`, `grantpt`, `unlockpt` and `ptsname`. The classes are `PosixFcntlModule`
(`fcntl`, `ioctl`, `flock`, `lockf`), `PosixTermiosModule`, `PosixResourceModule`, `PosixAccounts` (`pwd` and `grp`),
`PosixOsTty` (the `os` functions, installed by the last line of `PosixOsProc.install`), and the helpers `PosixLibcConsts`
(the integer constants, generated by `tools/gen_posix_tables.py --consts fcntl|termios|resource` from the reference
CPython: 79 names for `fcntl`, 234 for `termios`, 19 for `resource` plus `RLIM_INFINITY` and, on 3.15,
`RLIM_SAVED_CUR`/`RLIM_SAVED_MAX`) and `PosixArgs` (argument converters shared by these modules). None is initialised on
Windows, where `import fcntl`, `termios`, `resource`, `pwd` and `grp` raise `ModuleNotFoundError` and `os.openpty` does not
exist (`tests/conformance/os_platform.py`, `tests/test_import_runtime.py::test_windows_never_loads_posix_ffm`). The plan,
the measurements and the per-ticket results are in `work/n2_plan.md`, `work/n2/` and `HANDOFF.md`. The surfaces match the
reference interpreters (uv's CPython 3.13, 3.14, 3.15), not a distribution build; the lane-dependent texts and values
(`RLIM_INFINITY` as `-1` on 3.13/3.14 and `2**64 - 1` on 3.15, the buffer-length limits and wrong-type texts of
`fcntl`/`ioctl`, the 3.15 `DeprecationWarning`s) are selected with `LangVersion`. Warnings that Java code raises go
through the Python `warnings` module (`WarningsModule`), so `warnings.catch_warnings(record=True)` sees them on all three
lanes; the Java-side copies of `_defaultaction`, `_onceregistry` and the filters version are still not shared with the
Python module (open item).

**Buffers.** A buffer argument of `fcntl.fcntl` or `fcntl.ioctl` is copied into zero-filled native memory of
`max(len, 1024)` bytes plus 8 guard bytes, and exactly `len` bytes are read back: the kernel never sees heap memory and
may write a whole struct, so a short Python buffer cannot be overrun. `ioctl` with a writable buffer and
`mutate_flag` true writes the result back into the object, for any length; only `bytearray`, `array.array` and a
`memoryview` over a `bytearray` take that path. A writable `memoryview` over an `array` or an `mmap` returns `bytes`
instead (a reviewer's observation, not separately measured). The 8 guard bytes also reproduce the 3.14+ overflow check
(`SystemError` on 3.14, "Possible stack corruption" on 3.15, only for buffers up to 1024 bytes): a NUL at `buf[len]` and a
sentinel after it, not CPython's own sentinel value, which is not observable. Where the plan and CPython differed, the
implementation follows the measured CPython: the integer third argument of `ioctl` is range-checked (unlike
`fcntl.fcntl`'s, which is truncated to 32 bits), the `ioctl` request is a 32-bit mask on every lane, and `lockf` maps
`cmd` as CPython's C code does (exactly `LOCK_UN`; otherwise any `LOCK_SH` bit; otherwise any `LOCK_EX` bit; so
`LOCK_UN | LOCK_NB` is a `ValueError` and `LOCK_SH | 64` is accepted). A descriptor created by an `ioctl` request is not
adopted (rare; no request is special-cased).

**Lock waits (owner decision Q3, 2026-11-25).** `flock` without `LOCK_NB`, `F_SETLKW` and `F_OFD_SETLKW` through
`fcntl.fcntl`, and blocking `lockf` go through one function, `PosixFcntlModule.lockWait`. On the main thread, with
`_signal` imported, the wait is *polled*: the non-waiting command (`LOCK_NB`, `F_SETLK`, `F_OFD_SETLK`) is retried with
the back-off `PosixProc.await` uses (1 ms doubling to 50 ms), running pending Python handlers between tries. Handlers and
Ctrl-C therefore run at most about 50 ms late, and a handler that raises propagates without the lock being taken. A
polled wait loses what the kernel's queue gives: it is not FIFO (a later waiter can overtake an earlier one), the
kernel's `EDEADLK` detection does not apply (a deadlock waits forever instead of failing), and the lock can be acquired
up to 50 ms after it was released. Off the main thread, or before `_signal` is imported, a lock wait is one blocking
call, as in CPython. `tests/test_posix_libc.py::test_fcntl_lock_waits` covers the handler, the raising handler and a
waiter on another thread.

**Other blocking calls are not sliced.** `tcdrain`, `tcsendbreak`, `tcflow`, `tcsetattr` with `TCSADRAIN`/`TCSAFLUSH`
and any `ioctl` are one libc call. A Python signal handler does not run while the main thread is inside one of them. On
`EINTR` the pending handlers run and the call is retried (PEP 475); this is also done by `termios`, where CPython raises
`termios.error(4)` instead. The blocking behaviour of `tcdrain`, `tcsendbreak` and `tcsetattr(TCSADRAIN)` with output
still pending was never measured (only the no-pending-output case, which returns at once).

**Record locks and the JDK.** POSIX record locks (`lockf`, `F_SETLK`, `F_SETLKW`) belong to the process and are dropped
when *any* descriptor of that file is closed in the process. Under pyJVM that includes opens and closes the JDK makes on
its own (java.nio path operations, the importer reading a source file), so a lock on a file the runtime also touches can
disappear. `flock` and OFD locks (`F_OFD_*`) belong to the open file description and are unaffected. There is no guard
(CPython semantics); likewise Python can change the flags of a JVM-owned descriptor by number. `F_DUPFD` and
`F_DUPFD_CLOEXEC` with an integer argument create a descriptor, which is adopted (`PosixFds.adopt`) and closed through
`PosixFds.close`; `F_DUPFD`'s result is inheritable because Python asked for it.

**`resource` reports the JVM's limits.** `getrlimit`, `setrlimit`, `prlimit` and `getrusage` work on the JVM process.
HotSpot has already raised the soft `RLIMIT_NOFILE` to the hard limit before Python code runs (N1 gap 1.6 b), so
`getrlimit(RLIMIT_NOFILE)` reports `soft == hard` where CPython reports the launcher's soft limit. Lowering `RLIMIT_AS`,
`RLIMIT_DATA`, `RLIMIT_NPROC`, `RLIMIT_CPU`, `RLIMIT_FSIZE` or `RLIMIT_NOFILE` inside a JVM can stop the JVM itself
(allocation, thread creation, class loading). How HotSpot reacts to those limits was deliberately not measured (the one
exception is CPython's `test_fsize_*` tests below, which lower the soft `RLIMIT_FSIZE`, each in a fresh JVM; every JVM
survived), nothing was measured as root
(where the "not allowed" error cases would succeed), and there is no guard. Limits are read as unsigned (`rlim_t`): only
infinity is negative on 3.13/3.14. `-1` given to a setter means infinity on every lane (3.15 warns); `EPERM` is a
`ValueError` from `setrlimit` but a `PermissionError` from `prlimit`.

**`pwd` and `grp` go through libc**, so NSS sources are honoured (`getpwuid_r`, `getpwnam_r`, `getgrgid_r`,
`getgrnam_r`, buffer doubled on `ERANGE` up to 64 MiB then `MemoryError`; no Java parser of `/etc/passwd`). The
enumerations `getpwall`/`getgrall` use `set*ent`/`get*ent`/`end*ent` under one lock (`PosixAccounts.ENT_LOCK`, shared by
both databases) with every string copied inside it, and `end*ent` in a `finally`. Strings are decoded as UTF-8 with
`surrogateescape`; a NULL `pw_passwd`, `pw_gecos`, `pw_dir`, `pw_shell` or `gr_passwd` is `None`. Only the NSS sources
`files systemd` were exercised (JVM probe: 28 passwd and 56 group entries, equal to `getent`, from two threads); LDAP,
sssd and NIS, and entries that are not valid UTF-8, were not. An NSS module that misbehaves inside a JVM would show up as
a crash, not an exception.

**Pseudo-terminals.** `os.openpty()` is deliberately not libc `openpty`: it is `posix_openpt(O_RDWR | O_NOCTTY |
O_CLOEXEC)`, `grantpt`, `unlockpt`, `ptsname_r` and an `open` of the slave with `O_NOCTTY` and `O_CLOEXEC`, so both
descriptors are close-on-exec atomically and a JVM that is a session leader never acquires the pty as its controlling
terminal (which would later send the JVM `SIGHUP` when the master closes). Measured: `/proc/self/stat` `tty_nr` stays 0
throughout (JVM probe, and `test_openpty_never_gives_the_session_leader_a_controlling_terminal`). Both descriptors are
adopted. A failing slave open reports the slave path as the `OSError` filename where CPython's has none. Nothing stops a
program from calling `termios` on the JVM's own terminal: `tcsetattr` on descriptor 0 changes the user's terminal and
stops the JVM with `SIGTTOU` from a background process group, as it would CPython.

**`termios`.** `termios.error` is one class per module object with base `Exception` (not an `OSError`), raised with
`args == (errno, strerror)` and no `errno` attribute. `tcgetattr` returns CPython's list (the `cc` items are
one-byte `bytes`, except `VMIN` and `VTIME` which are ints when `ICANON` is off); `tcsetattr` reads the current state
with `tcgetattr` first (so a pipe gives `error(25)` before any attribute is looked at), accepts the list `tcgetattr`
returned, and sets the speeds with `cfsetispeed`/`cfsetospeed`. `tcgetwinsize`/`tcsetwinsize` use their own `ioctl`
handle; `tcsetwinsize` keeps the pixel fields. `tty`, `pty` (its `openpty`), `getpass` and `_pyrepl.fancy_termios`
import (`tests/test_posix_libc.py` imports them and looks at `getpass.unix_getpass` without calling it).
`_pyrepl.unix_console` no longer stops at `termios`; in the regenerated `STDLIB_STATUS.md` it stops at `_ctypes` (3.13) and
`unicodedata` (3.15).

**Descriptor arguments.** `fcntl` and `termios` (and `os.grantpt`/`unlockpt`/`ptsname`) accept an int, a bool or an
object with `fileno()` (`PosixArgs.fd`, CPython's `PyObject_AsFileDescriptor`); a bool warns
`RuntimeWarning: bool is used as a file descriptor`. `SelectModule.fdOf` is shared with `select` and was not given the
warning (a recorded gap, not exercised by a test). `os.ttyname` and `os.posix_openpt` take a plain C int.

**Absent.** `os.login_tty`, `forkpty`, `tcgetpgrp`, `tcsetpgrp`, `ctermid`, `getlogin`, `getgroups`, `getgrouplist` and
`setsid` stay absent (owner decision Q1; `login_tty` and `forkpty` need `fork`), so `pty.fork` and `pty.spawn` fail with
`AttributeError`. `os.chown`, `setreuid`, `setregid` and `setgroups` are also absent (outside N2). `PosixStructSeq` (the
factory for `pwd.struct_passwd`, `grp.struct_group`, `resource.struct_rusage` and `os.waitid_result`) accepts
construction from a sequence, pickling and copying, but has no `__replace__`, and the class is sealed, so assigning a
class attribute (`pwd.struct_passwd.x = 1`) raises `TypeError` where CPython allows it.

**`PYJVM_POSIX_FDS=nio`.** The `fcntl`, `resource`, `pwd` and `grp` shells in `StubModules` remain for this mode only
(N0 ticket 14 removes both); they no longer return made-up values: every `resource` function and `getpwall`/`getgrall`
raise `NotImplementedError`, the `pwd`/`grp` lookups raise `KeyError`. `termios` does not exist in `nio` mode
(`ModuleNotFoundError`), and the `os` pty functions are absent. The `_socket` shell is the one that still returns made-up
values (`gethostname()` is `"localhost"`, the default-timeout functions do nothing) until N3.

**CPython's own tests** (`work/n2/cpython_tests.md`, 3.15 lane, Linux, no controlling terminal). Identical to CPython:
`test_pwd` 3/3, `test_grp` 3/3, `test_openpty` 1/1, `test_tty` 4/4, `test_termios` 21/21, `test_ioctl` 4/4 (its
`IoctlTestsTty` class skips on both for lack of `/dev/tty`), `test_posixpath` expanduser 5/5, and `test_resource` 7/7
plus the five `test_fsize_*` tests, each in a fresh JVM, all passing with no JVM dying. Differing: `test_fcntl` 7 ok / 2
FAIL / 2 ERROR / 3 skipped (CPython 11/0/0/3), `test_pty` 3 ok / 3 ERROR of 6, `test_getpass` 46 ok / 4 ERROR of 50,
and the selected `test_subprocess`, `test_pathlib`, `test_shutil`, `test_os` and `test_tarfile` tests. No difference
traced to the N2 modules: fixed here 0, blocked by `fork` 5, by `sys.executable` 6, by N3 1 (`socket.socketpair`), unsafe
and not run 2, and 68 out of scope because of core gaps, mainly `memoryview.cast('i')` indexing past element 0, class-level
`mock.patch` decorators, `MagicMock` magic-method `delattr`, `copy.copy` of a `__slots__` class (`tarfile.TarInfo`),
and `os.DirEntry.is_junction` missing (which breaks `os.walk`, so `shutil.rmtree` on 3.15). `test_no_leaking` now reaches
`EMFILE` and fails only when it starts `sys.executable`. `test_resource`'s two `*_refcount` tests lower **hard** limits
(`RLIMIT_CPU` to 10^6 s, `RLIMIT_AS` to `2**64 - 2`) of the process that runs them and were not run under pyJVM; the same
objects passed to `resource.prlimit` on a `sleep 30` child give identical results on CPython and pyJVM.

**Speed** (3.15, warm, three rounds alternating, microseconds per call, CPython / pyJVM): `fcntl.fcntl(fd, F_GETFD)`
0.27 / 1.75, `pwd.getpwuid(os.getuid())` 6.8 / 16.0, `pwd.getpwall()` (28 entries) 256 / 278, `termios.tcgetattr(slave)`
0.60 / 1.20, `resource.getrlimit(RLIMIT_NOFILE)` 0.33 / 1.47; none is more than 10x. Most of the gap of the one-line calls
is the cost of the benchmark's Python `lambda` in pyJVM (1.4 us for an empty one against 0.02 us), not the FFM downcall.
Not done: `getrlimit` and `tcgetattr` open an `Arena.ofConfined()` per call and `PosixFcntlModule`'s `retry(() -> ...)`
allocates a lambda per call; neither matters at these speeds.

**For anyone writing tests that use these modules** (`work/n2_plan.md` 2.3): never call a `termios`/`TIOC*` function on
descriptors 0-2, `/dev/tty` or any terminal the script did not create with `os.openpty()`; never open a terminal without
`O_NOCTTY`; run pty scripts under `timeout`, in their own session, with stdin from `/dev/null`; a pty moves data
asynchronously, so `select` before reading and never read an echo after a flush (a draft case hung on exactly that). Never
lower a hard limit; inside a JVM the only soft limits to touch are `RLIMIT_NOFILE` minus one (restored in a `finally`) and
`RLIMIT_CORE`; lower limits for real only with `resource.prlimit` on your own `sleep 30` child; never run as root;
`test_subprocess.test_preexec_fork_failure` (soft `RLIMIT_NPROC` 0) is never run under pyJVM. Test hooks: the system
properties `pyjvm.test.pwbuf` (first `pwd`/`grp` buffer size) and `pyjvm.test.ptsbuf` (first pty name buffer size).

**Obligations for N3 and N6.** N3 (sockets) reuses `PosixArgs.fd` for descriptor arguments, the buffer helpers of
`PosixArgs` (`readBuffer`, `checkBufferLength`, `isIndex`) and `PosixFcntlModule.lockWait`'s shape (polled wait with
`PosixProc.backOff` on the main thread) for blocking calls, and must adopt every descriptor it creates. N6 `_curses` can
test under a pty with `os.openpty` and `termios` and must open any terminal with `O_NOCTTY`.

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
modules no longer clash), importable shells of `_posixsubprocess`, `fcntl`, `pwd`, `grp` (every operation raised when
this was written; they are real on Linux since N1/N2 and remain shells only under `PYJVM_POSIX_FDS=nio`), and the
wait-status helpers of `os`.

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
  implicit base of PEP 695 classes), so the pure-Python `typing` imports on the 3.13 and 3.15 lanes. On Linux `_socket` is
  the only import-only shell left; `fcntl`, `pwd`, `grp`, `resource` and `_posixsubprocess` are shells only under
  `PYJVM_POSIX_FDS=nio` (real since N1 and N2, see "POSIX native layer").
- No `faulthandler` and no real sockets (`_socket` is a shell on POSIX until N3). `fcntl`, `termios`, `pwd`, `grp`,
  `resource`, subprocess spawning and `os.waitpid` are real on Linux, but tests that spawn interpreters
  (`script_helper`) still cannot run, because `sys.executable` is the string `"pyjvm"` (see "Processes").
- Test modules that depend on CPython internals (`sys.getrefcount`, `gc` tracking, `_testcapi`, bytecode, `marshal` of
  code objects) report those tests as errors or skips; the differing ones are listed by `run_cpython_test.py`.
- `f_globals` of a live frame is the live module dict (`f.f_globals is globals()`). Traceback entries are separate frame
  objects, so `tb.tb_frame is sys._getframe()` is still False, and frames have no `f_builtins`.
