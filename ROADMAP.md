# pyJVM: Roadmap to Full CPython 3.15 + Stdlib on the JVM, and Kotlin-Style Java Interop

Nov 10, 2026 · @m

## Where pyJVM stands and what "done" means

pyJVM compiles Python 3.15 AST directly to Java 21 bytecode with a small Java runtime; it compiles 2,247 of 3,000 pinned CPython test bodies (74.9%) but runs only 68 of them unchanged, and none of the stdlib yet. The gap to "fully compatible" is mostly runtime and stdlib, not syntax. Figures are from the [README](https://github.com/rbroderi/pyJVM) and [CPYTHON\_REGRESSION\_PROBE.md](https://github.com/rbroderi/pyJVM/blob/main/CPYTHON_REGRESSION_PROBE.md) as of today.

| Signal | Current value |
| --- | --- |
| Pinned 3,000-body compile probe | 2,247 compile / 753 unsupported / 0 compiler errors |
| Legacy 358-body probe | 325 compile / 33 unsupported |
| Unchanged CPython bodies executed | 68 bodies / 85 executions |
| Project differential cases | 195 passing |
| Top unsupported reasons | match (283), exec (63), eval (51), compile (51), contextmanager (26), open (25), frozendict (19) |

Known structural gaps the plan must close, beyond the probe list:

- dict/set still use Java containers for key protocol, not Python hash/eq tables.
- No runtime compiler, so `exec`/`eval`/`compile` and the `ast` module cannot work on the JVM.
- Deferred annotations (PEP 649/749) raise `NotImplementedError`.
- No stdlib import path: `copy`, `weakref`, `binascii`, `re`, `os` and friends are external.
- Native-extension strategy is undefined.

**Definition of done.** Three gates, measured on CPython's own suite rather than fixtures:

1. **Language-complete:** 3,000-body probe at 100% compile, and the compiler self-hosts (pyJVM compiles pyJVM and the result passes the same gates).
2. **Stdlib-complete:** `python -m test` (libregrtest) runs on pyJVM itself, with a per-module pass rate of at least 95% on the pure-Python and Tier A/B modules defined in Phase 4, and every skip carries a tagged reason.
3. **Interop-complete:** a mixed Gradle project with Java, Kotlin and Python sources compiles in one build, each language calling the others with static types and no reflection on the hot path.

## Execution model: one lead, many Sonnet workers

Run this in Claude Code with a Fable 5.2 lead session that owns the plan, the baselines and merges, and Sonnet 5.5 subagents that each take one ticket in its own git worktree. Workers never edit baselines, manifests or this plan; the lead does.

**Lead responsibilities**

1. Pick the next ready tickets from the ticket list (dependencies met, no file-ownership overlap) and spawn up to 4–6 workers in parallel.
2. Hand each worker the brief template filled in, plus the exact failing probe identities or CPython test bodies it targets.
3. On return: run the full gate locally, review the diff for placeholder semantics, then merge and bump the baseline floors.
4. Every \~10 merged tickets, re-run the 3,000-body probe and `--unsupported-summary`, then re-rank remaining tickets by failure frequency.
5. Expand the manifests (compile and execution) only when the current one stops producing new top reasons.

**Worker ground rules** (paste into every brief)

- Work only inside the files your ticket owns; if you must touch a shared file, keep the edit minimal and say so in the summary.
- Every behaviour change ships with differential cases in `tests/conformance` that pass against CPython 3.15 and on the JVM.
- Where an unchanged CPython test body exercises the feature, add it to `tools/cpython_execution_manifest.txt` in a separate commit the lead can accept or drop.
- No placeholders: unimplemented paths raise an explicit `NotImplementedError` or stay unsupported in the compiler. Never return `None`, empty containers or stub objects to make a test pass.
- Keep every runtime Java method under 8,000 bytecode bytes (HotSpot's huge-method limit); the project already lost 4.84x on collections when the dispatcher crossed it.
- Run `benchmarks/run.py` before and after; report head/base ratios. Above 1.10 on any benchmark, explain or fix.
- End with a summary: what now works, probe identities that moved, what stays unsupported and why.

**Merge gate** (CI, all must pass)

- Focused differential corpus: 100% pass, 0 unsupported.
- 3,000-body probe: zero compiler errors, compile count at or above the floor, no previously compiling body regresses.
- Strict execution lane: all listed bodies pass.
- Benchmark regression gate.
- New from Phase 0: method-size lint on the runtime jar; libregrtest pass-rate floor once Phase 5 starts.

**Parallelism hazard.** The runtime grew as a few large classes and a shared dispatcher, so parallel workers will collide. Phase 0 splits it into per-type files with registered dispatch before any wide fan-out.

## Phase 0–1: foundations, then the remaining language

Phase 0 is lead-run plumbing that makes parallel work safe; Phase 1 closes the 753 unsupported probe bodies, led by pattern matching and the self-hosted compiler that `exec`/`eval`/`compile` need.

**Phase 0 — foundations (lead plus 2 workers, serial)**

- Split the Java runtime into one file per builtin type plus a registered slot table (`tp_add`, `tp_getattro`, etc.), replacing the central dispatcher's switch. This mirrors CPython's type slots and lets workers own a type each.
- Add the method-size lint and a `@Generated` boundary so emitted helpers never inflate runtime methods.
- Add a `pyjvm315-probe --owner-map` report that tags each unsupported reason with the runtime file or compiler pass that owns it; the lead uses it to assign tickets.
- Pin a CPython 3.15 release (not rc) and re-baseline both probes against it.

**Phase 1 — language completion**

1. **Structural pattern matching (283 bodies).** Lower `match` in the compiler to a decision tree: literal, capture, wildcard, value, sequence, mapping, class (`__match_args__`), OR and AS patterns, guards. Runtime helpers for `Sequence`/`Mapping` ABC checks. Split into 3 tickets: literal/capture/sequence, mapping/class, OR/AS/guards plus binding-consistency errors.
2. **Self-hosted compiler.** Required for `exec`, `eval`, `compile`, `ast`, `symtable`, `dis` and any import of `.py` at runtime. Steps:
   1. Port CPython's tokenizer and PEG parser to run on the JVM. Preferred path: extend `Tools/peg_generator` (pegen) with a Python-target emitter that pyJVM then compiles; fallback is a hand-written Java port.
   2. Implement `_ast` node classes in the runtime so `ast.parse` returns real objects.
   3. Compile the `pyjvm315` compiler package with itself; load generated classes through a runtime `ClassLoader` (hidden classes via `Lookup.defineHiddenClass` for `exec`, so they can be unloaded).
   4. `exec`/`eval` with explicit globals/locals dicts: compile to a code object whose name resolution goes through the supplied mappings rather than static fields.
   5. Bootstrap gate: stage-2 compiler output is byte-identical to stage-1 on the full corpus.
3. **Deferred annotations (PEP 649/749).** Emit `__annotate__` functions, `annotationlib` formats (VALUE, FORWARDREF, STRING), and annotation-only assignments in classes and modules.
4. **Generator and frame corners.** `sys._getframe`, `f_back`, `f_lineno` writes, `gi_frame`/`cr_frame`, `sys.settrace`/`sys.setprofile` and `sys.monitoring` (PEP 669) behind an instrumented compile mode, since `pdb`, `coverage` and `bdb` tests need them.
5. **Remaining names from the probe.** `contextlib.contextmanager` falls out of Phase 3 imports; `open` falls out of `_io` in Phase 4; `frozendict` is new in 3.15 and belongs with the dict rewrite in Phase 2.
6. **Type-parameter syntax (PEP 695/696)** and `type` aliases, `TypeVar` defaults, if any remain unsupported in the probe.

Exit gate: 3,000/3,000 compile; bootstrap gate green; then expand the compile manifest to every `Lib/test/test_*.py` file.

## Phase 2: object model and builtin type fidelity

Replace Java-backed shortcuts with Python-exact representations, because the stdlib depends on dict ordering, hash/eq callbacks, code-point strings and subclassable builtins. This phase runs in parallel with Phase 1 once Phase 0 lands; one worker per type.

| Area | Required change | Why the stdlib needs it |
| --- | --- | --- |
| `dict` / `set` / `frozendict` | Own compact open-addressing table (CPython layout: index array + ordered entries), Python `__hash__`/`__eq__` callbacks, mutation-during-iteration errors, key-sharing optional | Ordering guarantees, `collections`, `functools.lru_cache`, `enum`, class namespaces |
| `str` | Code-point string with PEP 393-style storage (Latin-1 `byte[]`, UCS-2, UCS-4 `int[]`), lone surrogates allowed, `java.lang.String` cached lazily for interop | `len`/indexing on astral text, `codecs`, `re`, `unicodedata` |
| `int` / `float` / `complex` / `bool` | Finish subclass support (`class C(int)`), `int.to_bytes`/`from_bytes`, `float.hex`, `__index__` everywhere, exact `repr` across binary64 | `enum.IntEnum`, `decimal`, `struct`, `fractions` |
| Builtin subclassing | Every builtin type subclassable with `__new__`/`__init__` split, `__dict__` and `__slots__`, `__class__` assignment rules | `collections.OrderedDict`, `UserDict`, `pathlib`, `enum` |
| `list` / `tuple` / `range` / slices | Timsort port for exact comparison traces, `__reversed__`, `__length_hint__` everywhere, list sort with `key` errors mid-sort | `test_sort`, `heapq`, `bisect` |
| Buffer protocol | `memoryview` formats, `cast`, multi-dim shapes, export counting that blocks `bytearray` resize | `struct`, `array`, `io`, `hashlib`, sockets |
| `str.format` / `bytes %` | Brace parser, `string.Formatter` hooks, `format_spec` mini-language for all types, locale `n` | `logging`, `string`, `argparse` |
| Weak references | `weakref.ref`/`proxy`, callbacks, `WeakValueDictionary` support via Java `WeakReference` + `ReferenceQueue` | `weakref`, `functools`, `copy`, `abc` caches |
| Finalization and GC | `__del__` via `java.lang.ref.Cleaner`, `gc.collect()` triggers System.gc plus queue drain, `gc.get_referrers` explicitly unsupported | `tempfile`, `weakref.finalize`, resource cleanup tests |
| Exceptions | Full 3.15 hierarchy, `ExceptionGroup`/`except*`, `add_note`, exact `traceback` formatting with carets | `traceback`, `unittest`, `asyncio.TaskGroup` |
| Threads | JVM threads are real; adopt free-threaded semantics (as in CPython's free-threaded build): per-object locking for dict/list mutation, `_thread`, `threading.local` | `threading`, `queue`, `concurrent.futures` |

Exit gate: CPython `test_dict`, `test_set`, `test_unicode`, `test_long`, `test_float`, `test_list`, `test_tuple`, `test_exceptions`, `test_weakref` run unchanged at the agreed pass rate once Phase 3 can import `unittest`.

## Phase 3: import system and pure-Python stdlib

Ship CPython's own `Lib/` compiled by pyJVM rather than rewriting it; the work is a real `importlib` bootstrap plus a precompiled stdlib jar. Today imports are resolved statically at compile time, which cannot load the stdlib, plugins or `sys.path` entries.

1. **Runtime import machinery.** Run CPython's `importlib._bootstrap` and `_bootstrap_external` unchanged on pyJVM, backed by Java built-ins for `_imp`, `_io` (minimal), `marshal` stubs replaced by a class-file cache, `_warnings`, `_weakref` and `posix` path calls. Keep the existing static fast path as an optimization when the target is known at compile time.
2. **Finders and loaders.**
   1. `JarModuleFinder`: precompiled stdlib and app modules as `.class` inside jars on the classpath (the default, fastest path).
   2. `SourceFileLoader` replacement: compiles `.py` at import time via the self-hosted compiler and caches `.class` files in `__pycache__/*.pyjvm-315.class`, keyed on source mtime and hash like `.pyc`.
   3. Namespace packages, `importlib.resources`, `importlib.metadata`, zipimport.
3. **`sys` module completeness.** `sys.path`, `sys.modules`, `sys.meta_path`, `sys.path_hooks`, `sys.flags`, `sys.implementation` (name `pyjvm`, `cache_tag` `pyjvm-315`), `sys.platform` reporting the host OS, `sys.getrecursionlimit` mapped to a depth counter with JVM stack-size guidance.
4. **Stdlib build.** A build step compiles all of `Lib/` (minus `test/` and platform-specific dirs) into `pyjvm-stdlib-3.15.jar`. Track per-module compile status in a manifest with the same no-regression rule as the probe.
5. **Prefer pure-Python fallbacks.** CPython already ships Python implementations for many C accelerators. Configure the import of `_pyio`, `_pydatetime`, `_pydecimal`, `_py_abc`, `_pylong`, `pickle` (Python `Pickler`), `json` (no `_json`), `heapq`, `bisect`, `operator`, `functools`, `stat`, `warnings`, `collections` (minus `deque`) to use the fallback first, and only replace with Java later for speed.
6. **unittest and libregrtest.** First milestone of this phase: `import unittest` and run one CPython test module unchanged on pyJVM, with results compared to CPython by the existing differential runner. This retires the fixture-based execution lane.

Exit gate: `python -m unittest test.test_grammar` and 20 named core test modules run end to end on pyJVM through libregrtest.

## Phase 4: native module replacement layer

Every CPython C module without a pure-Python fallback gets a Java implementation, tiered by how directly the JDK covers it. One worker per module; each ticket's acceptance test is that module's own `Lib/test` file.

| Tier | Strategy | Modules |
| --- | --- | --- |
| A: no work | Use CPython's Python fallback (Phase 3) | `_pyio`, `_pydatetime`, `_pydecimal`, `_py_abc`, `pickle`, `json`, `heapq`, `bisect`, `operator`, `functools`, `stat`, `warnings`, `_strptime`, `xml.etree` |
| B: JDK-backed Java | Thin Java module over JDK APIs, matching CPython behaviour exactly | `math`/`cmath` (StrictMath plus correctly rounded fixes), `time`, `zlib`/`binascii` (java.util.zip, CRC32), `_hashlib`/`_md5`/`_sha*`/`_blake2` (MessageDigest, Bouncy Castle for BLAKE2/SHA-3 if needed), `_socket` and `select` (java.nio channels, Selector), `_ssl` (SSLEngine), `_bz2`/`_lzma` (bundled pure-Java codecs), `_sqlite3` (sqlite-jdbc), `_csv`, `array`, `_struct`, `unicodedata` (generated tables, not ICU, for exact version match), `_locale` |
| C: must write from scratch | No JDK equivalent with Python semantics | `_sre` (port CPython's regex engine; `java.util.regex` differs in backtracking, Unicode and flags), `itertools`, `_collections.deque`, `_random` (exact MT19937), `_codecs` and all encodings, `_string`, `_contextvars` (HAMT), `_tokenize`, `_opcode`/`dis` (map to pyJVM's own code objects), `_json` speedups later, `_asyncio` (pure-Python fallback first) |
| D: OS via Panama FFM | Java 21 Foreign Function and Memory API for syscalls the JDK hides | `posix` (stat, open flags, fd ops, `os.pipe`, `os.dup2`, uid/gid, `os.environ` writes), `fcntl`, `termios`, `pwd`/`grp`, `resource`, `mmap`, `_posixsubprocess` (spawn via `posix_spawn`, not fork), `signal` (sun.misc.Signal plus FFM) |
| E: explicitly unsupported | Document and skip-tag in libregrtest | `os.fork` and `os.forkpty` (unsafe on a multithreaded JVM), `_testcapi`/`_testinternalcapi`, `ctypes` (until FFM bridge exists), `tkinter`, `curses`, `readline` (JLine later) |

**C extensions (later track).** Third-party wheels with C code need either the HPy ABI or a CPython C-API emulation layer over Panama FFM, as GraalPy does. Treat this as a separate project after Phase 5; it does not block stdlib compatibility.

Exit gate: every Tier A–D module imports and its own test file meets the pass-rate floor; Tier E modules raise `ImportError` with a clear message and are skip-tagged.

## Phase 5: full Lib/test execution

Switch the primary metric from compile coverage to unchanged CPython tests passing on pyJVM, run by libregrtest on pyJVM itself.

- **Tagging, not editing.** Keep CPython's test files byte-identical. Expected failures live in a pyJVM-side tag file (`tools/regrtest_tags/<module>.txt`: test id, status, reason, owning ticket), the way GraalPy and Jython manage it. A tag without a reason or ticket fails CI.
- **Nightly full run** with per-module pass rate published as an artifact; PR runs execute only modules touched by the change plus a fixed smoke set.
- **Ratchet.** A test that passes on `main` and fails on a PR blocks the merge, mirroring the existing per-identity compile baseline.
- **Triage loop for the lead.** Each week, group failures by exception type and top traceback frame, open tickets for the top 20 groups, and assign them to workers.
- **CPython-implementation details.** Tests guarded by `test.support.cpython_only`, refcount checks and `sys.getrefcount` are skip-tagged with reason `impl-detail`, not counted as failures.

Exit gate: 95% of non-`impl-detail` tests pass across all Tier A–D modules; 100% on the language and builtin core (`test_grammar`, `test_syntax`, `test_dict`, `test_unicode`, `test_descr`, `test_generators`, `test_coroutines`, `test_patma`, `test_exceptions`).

## Phase 6: performance tier

Once semantics are locked, make compiler-provable code run near Java speed, which the README already names as the goal. Start this only after Phase 5's ratchet exists, so optimizations cannot silently break behaviour.

- **invokedynamic call sites** for attribute load, binary ops and calls, with type-guarded inline caches and `MutableCallSite` invalidation on class mutation.
- **Type-hint-driven fast paths.** Where annotations and local inference prove `int` within `long` range, emit primitive `long` with overflow checks that deopt to `BigInteger` (`Math.addExact`).
- **Escape analysis friendliness:** small immutable boxes, no frame object allocation unless a frame is observed (lazy materialization for tracebacks and `sys._getframe`).
- **Startup:** AppCDS archive for the stdlib jar; optional GraalVM native-image profile.
- **Benchmarks:** add pyperformance's pure-Python suite alongside the existing Java-comparison microbenchmarks, and track it in the same regression gate.

## Java and Python interop, Kotlin-style

Kotlin's interop works because its compiler reads Java class files at compile time and emits ordinary, statically typed JVM classes that Java can call without a runtime bridge. pyJVM already emits real class files ahead of time, so it can do the same, which neither Jython nor GraalPy can fully do. Type hints are the static types.

### Python calling Java

1. **Imports.** `from java.util import ArrayList` and `import java.time as jt` resolve through a `JavaPackageFinder` on `sys.meta_path` for runtime use, and through the compiler's class-file reader at compile time. Packages outside `java`/`javax`/`jdk` are reached as `from jvm.com.example import Foo` to avoid shadowing Python packages.
2. **Static binding when types are known.** If the receiver's type is known (annotation, constructor call, Java return type), the compiler emits `invokevirtual`/`invokestatic` with the chosen overload, just as Kotlin does. Unknown receivers fall back to an `invokedynamic` site with runtime overload resolution and caching.
3. **Overload resolution rules,** documented and tested: exact match, then widening, then Python-to-Java conversion, then varargs; ambiguity is a compile error with candidates listed (statically) or `TypeError` (dynamically).
4. **Value mapping.**

| Python | Java | Rule |
| --- | --- | --- |
| `int` | `long`/`int`/`short`/`byte`, `BigInteger` | Checked narrowing; `OverflowError` on loss |
| `float` | `double`/`float` | Direct |
| `bool` | `boolean` | Direct |
| `str` | `String`/`CharSequence` | Code-point copy, cached both ways |
| `bytes` / `bytearray` | `byte[]` | Copy for `bytes`; shared buffer for `bytearray` when declared |
| `None` | `null` | Java `@Nullable` and `Optional[T]` hints line up |
| `list` / `dict` / `set` | `List` / `Map` / `Set` | Python containers implement the Java interfaces directly; no copy |
| callable | any SAM interface | Automatic SAM conversion, like Kotlin lambdas |
| Java `Iterable` / `AutoCloseable` / `Map` | iteration / `with` / mapping protocol | Protocol adapters on Java objects |

5. **Properties.** `getFoo()`/`isFoo()`/`setFoo()` appear as attribute `foo`, as Kotlin synthesizes properties.
6. **Subclassing Java.** `class Task(java.lang.Runnable)` or `class MyList(java.util.AbstractList)` compiles to a real JVM class that extends or implements the Java type. Methods with matching names override; annotated parameters choose the overridden signature; `super()` reaches Java super methods.
7. **Exceptions.** Java `Throwable` is catchable in `except` (`java.lang.Exception` behaves as a Python exception class). Python exceptions crossing into Java are `PyException extends RuntimeException` with the Python type and traceback attached; Java stack frames are merged into Python tracebacks.
8. **Generics and arrays.** `list[str]` hints map to `List<String>` signatures; `jarray(int, 10)` and `java.array[int]` create primitive arrays.

### Java (and Kotlin) calling Python

1. **Typed facades.** For each module, the compiler emits a final class (`com.example.mymod.MyModKt`-style naming, configurable) with `public static` methods for top-level functions. Annotated signatures become real Java types; unannotated parameters become `Object`.
2. **Decorators mirroring Kotlin's JVM annotations** (in a small `jvm` module):

| Decorator | Kotlin analogue | Effect |
| --- | --- | --- |
| `@jvm.export` | public declaration | Class or function appears in the Java API |
| `@jvm.static` | `@JvmStatic` | Static method on the facade or class |
| `@jvm.overloads` | `@JvmOverloads` | One Java overload per default-argument prefix |
| `@jvm.name("x")` | `@JvmName` | Java-visible name |
| `@jvm.field` | `@JvmField` | Public field instead of accessor |
| `@jvm.throws(IOException)` | `@Throws` | Checked-exception declaration |
| `@jvm.annotate(...)` | annotation use | Emits arbitrary Java annotations (Spring, JUnit, JPA) |

3. **Classes.** An exported Python class becomes a Java class with typed constructors, methods, and getters/setters for annotated attributes; `@dataclass(frozen=True)` can emit a Java `record`. Python semantics (dynamic attributes, MRO) still hold inside.
4. **Nullability metadata.** Emit `@Nullable`/`@NotNull` (JSpecify) from hints so Kotlin sees correct platform types.
5. **Embedding API** for scripting use: `Python.runtime().eval("...")`, a JSR-223 `ScriptEngine`, and `PyObject` handles with `call`, `getAttr`, `as(Class)`. Depends on the Phase 1 self-hosted compiler.

### Build and tooling

- **Joint compilation.** A Gradle plugin (and Maven plugin) compiles `src/main/python` beside Java and Kotlin. For Python↔Java cycles, pyJVM first emits Java-visible stubs (signatures only), then javac/kotlinc compile against them, then pyJVM compiles fully against the resulting classes, the same strategy Groovy and kapt use.
- **Stub generation for editors.** Generate `.pyi` stubs for Java classes on the classpath so Pyright/mypy and IDEs type-check Python code that uses Java.
- **Packaging.** Pure-Python wheels are installed normally and compiled on first import (Phase 3 cache) or ahead of time by the Gradle plugin into the application jar.

### Interop milestones

1. I-1, Python calls Java dynamically (finder, invokedynamic, value mapping, SAM, properties, exceptions). Can start after Phase 3.
2. I-2, static binding from type hints, plus subclassing Java classes.
3. I-3, Java/Kotlin call Python through typed facades and `jvm` decorators.
4. I-4, Gradle/Maven joint compilation, `.pyi` stubs, embedding API and JSR-223.
5. I-5, framework proof: a Spring Boot service with controllers written in Python and a Kotlin module calling it, run in CI.

## Sonnet brief template and ticket list

The lead fills this template per ticket and passes it as the subagent prompt; each ticket below is sized for one Sonnet session (roughly one PR).

```markdown
# Ticket {ID}: {title}
Repo: rbroderi/pyJVM, worktree {path}, branch ticket/{ID}
Goal: {one sentence of observable behaviour}
Targets: {probe identities or CPython test ids that must move}
Owns: {files/dirs you may change freely}
May touch minimally: {shared files}
Do not touch: tools/*_baseline.json, manifests, docs/PLAN

Steps:
1. Read README.md, JVM_BACKEND.md and the owning code before editing.
2. Write failing differential cases in tests/conformance first; confirm they fail.
3. Implement. No placeholder semantics; unsupported paths raise explicitly.
4. Run: focused conformance, 3,000-body probe, strict execution lane,
   benchmarks/run.py, method-size lint.
5. Summarise: what works, identities moved, ratios, remaining gaps.

Acceptance: {ticket-specific criteria}
```

| ID | Ticket | Depends on | Acceptance |
| --- | --- | --- | --- |
| P0-01 | Split runtime into per-type files with slot table | none | All gates green; no benchmark above 1.05 |
| P0-02 | Method-size lint and probe `--owner-map` | P0-01 | CI fails on a method over 8,000 bytes |
| P0-03 | Re-pin to CPython 3.15 final and re-baseline | none | Both probes rebaselined, counts recorded |
| P1-01 | `match`: literal, capture, wildcard, sequence | P0-01 | Matching `test_patma` bodies compile and pass |
| P1-02 | `match`: mapping and class patterns | P1-01 | Same, plus `__match_args__` cases |
| P1-03 | `match`: OR/AS, guards, binding errors | P1-02 | All 283 Match bodies compile |
| P1-04 | Tokenizer on JVM | P0-01 | `tokenize` output identical to CPython on `Lib/` |
| P1-05 | pegen Python-target parser compiled by pyJVM | P1-04 | `ast.dump` identical to CPython on all of `Lib/` |
| P1-06 | `_ast` runtime node types | P1-05 | `test_ast` core cases pass |
| P1-07 | Self-compile the compiler; bootstrap check | P1-06 | Stage-2 output byte-identical to stage-1 |
| P1-08 | `exec`/`eval`/`compile` with hidden classes | P1-07 | 165 probe bodies using them compile and run |
| P1-09 | PEP 649/749 deferred annotations | P0-01 | `test_type_annotations` passes |
| P1-10 | Frames: `_getframe`, `f_back`, settrace, `sys.monitoring` | P0-01 | `test_sys_settrace` core cases pass |
| P2-01 | Compact Python dict with hash/eq callbacks | P0-01 | `test_dict`, `test_ordered_dict` pass |
| P2-02 | Set/frozenset on the new table; `frozendict` | P2-01 | `test_set` passes; 19 frozendict bodies compile |
| P2-03 | Code-point `str` representation | P0-01 | `test_unicode` astral cases pass |
| P2-04 | Builtin subclassing for all core types | P2-01, P2-03 | `class C(int/str/dict/list)` cases pass |
| P2-05 | Timsort port | P0-01 | `test_sort` passes |
| P2-06 | Buffer protocol and `memoryview` formats | P0-01 | `test_memoryview`, `test_buffer` core pass |
| P2-07 | `str.format`, `string.Formatter`, bytes `%` | P2-03 | `test_format`, `test_string` pass |
| P2-08 | Weakrefs and finalization | P0-01 | `test_weakref` core cases pass |
| P2-09 | ExceptionGroup, `except*`, notes, traceback carets | P0-01 | `test_exception_group`, `test_traceback` core pass |
| P2-10 | Threads and per-object locking | P2-01 | `test_threading` core cases pass |
| P3-01 | Run `importlib._bootstrap` on pyJVM | P1-08 | `import` of an uncompiled `.py` works at runtime |
| P3-02 | Jar finder and `__pycache__` class cache | P3-01 | Second import loads cached classes |
| P3-03 | `sys` module completeness | P3-01 | `test_sys` core cases pass |
| P3-04 | Stdlib jar build and per-module manifest | P3-02 | All of `Lib/` compiles or is tagged |
| P3-05 | `unittest` and libregrtest on pyJVM | P3-04 | `python -m test test_grammar` runs on JVM |
| P4-xx | One ticket per Tier B/C/D module | P3-05 | That module's own test file meets the floor |
| P5-01 | Tag files, nightly run, PR ratchet | P3-05 | CI blocks a regressed passing test |
| P6-01 | invokedynamic attribute/call sites | P5-01 | pyperformance geomean improves; ratchet green |
| I-01 | Java package finder and dynamic calls | P3-01 | Python uses `java.util` collections and SAM lambdas |
| I-02 | Static binding from hints; subclass Java | I-01 | Generated bytecode has direct invokes; Runnable subclass runs |
| I-03 | Typed facades and `jvm` decorators | I-02 | Java test calls Python functions with typed signatures |
| I-04 | Gradle plugin, joint compile, `.pyi` stubs | I-03 | Mixed Java/Kotlin/Python sample builds |
| I-05 | Embedding API and JSR-223 | P1-08, I-03 | `ScriptEngine.eval` passes JSR-223 TCK subset |
| I-06 | Spring Boot proof application | I-04 | Sample service passes its tests in CI |

## Open decisions and risks

Four decisions should be made by you before workers start, because they change many tickets at once.

- **Parser route (P1-05).** pegen with a Python target keeps the grammar in sync with CPython for free; a hand-written Java parser starts faster but drifts every release. Recommended: pegen.
- **Threading model (P2-10).** Free-threaded semantics fit the JVM and match CPython's free-threaded build; a global lock is simpler but wastes the JVM. Recommended: free-threaded with per-object locks.
- **`int` mapping at the Java boundary (I-03).** Exporting annotated `int` as `long` with overflow checks is ergonomic; `BigInteger` is exact but awkward from Java. Recommended: `long` by default, `@jvm.bigint` opt-in.
- **License.** pyJVM is GPL-3.0, and the stdlib jar bundles PSF-licensed CPython code. GPL also affects anyone linking Java apps against the runtime; if Java/Kotlin interop for third parties is a goal, consider a runtime exception or a separate license for the runtime jar.

Risks the lead should watch:

- **Scale.** Full stdlib compatibility took Jython and GraalPy years with dedicated teams. Expect Phase 4 alone to be 60+ tickets.
- **Subagent drift.** Workers may "pass" tests with special-casing. The no-placeholder rule, the ratchet and lead diff review are the defence; spot-check one merged ticket in five in depth.
- **Merge contention** on the compiler's lowering pass and the runtime slot table; schedule tickets that share those files serially.
- **Performance regressions** from correctness work (dict rewrite, code-point strings); keep the benchmark gate strict and fix rather than waive.
