# pyjvm315

Experimental **Python 3.15 source/AST → JVM `.class` transpiler**, inspired by
BeeWare VOC's separation of Python semantics from JVM bytecode emission, but
implemented against the modern Python AST and a new runtime/object model.

## 0.9 status

The compiler emits JVM class files directly; user programs are not translated
into Java source. A small Java runtime supplies dynamic Python semantics while
compiled code remains ordinary JVM bytecode.

### Implemented language/runtime surface

- module code, packages, same-project absolute imports, and relative imports
- Python function objects and runtime argument binding
- positional-only, positional/keyword, keyword-only, defaults, `*args`, `**kwargs`
- first-class functions and recursion
- nested functions, lexical closures and `nonlocal`
- lambdas
- general function decorators with Python evaluation/application order
- module globals and `global`
- adaptive arbitrary-precision integers: `Long` fast path, `BigInteger` overflow path
- floats, strings, booleans and `None`
- arithmetic, bitwise operations, shifts, power, comparisons, membership
- `if`, `while`, `for`, loop `else`, `break`, `continue`, including loop `else` in generators
- `try`/`except`/`else`, `try`/`finally`, explicit causes, and implicit exception context
- `return`, `break`, and `continue` correctly unwind nested `finally` blocks
- `with` / user-defined context managers, including exception suppression and cleanup on `return`/`break`/`continue`
- lists, tuples, dicts, sets, slicing and item assignment/deletion
- exact and extended starred unpacking
- starred list/tuple/set literals and `**` dictionary unpacking
- list/set/dict comprehensions with non-leaking comprehension scope
- assignment expressions (`:=`), including containing-scope binding from comprehensions
- lazy generator functions with suspended JVM state, `yield`, `next()`, `send()`, `throw()`, and `close()`
- lazy generator expressions, including nested `for` clauses and filters
- `yield from` with delegated `send()`, `throw()`, `close()`, and delegated return values
- generator `try`/`except`/`finally`, including yielding `finally` suites, cleanup during `close()`, and persisted bare re-raise state
- generator-aware `with` blocks that preserve managers across suspension and injected exceptions
- generator loop iterators/locals preserved across suspension
- classes, multiple inheritance and C3-style MRO for the implemented class model
- instance attributes and bound methods
- `type`, `isinstance`, `issubclass`, `callable`
- `getattr`, `setattr`, `hasattr`, `delattr`
- common builtins: `len`, `range`, `enumerate`, `zip`, `sum`, `min`, `max`,
  `any`, `all`, `abs`, `sorted`, `reversed`, `iter`, `next`, and container constructors
- a growing core of `str`, `list`, `dict`, and `set` methods
- differential CPython/JVM conformance runner, including normalized exception comparison

### Integer representation

Python integers use an adaptive representation:

```text
ordinary value            overflow / huge literal
      │                            │
      ▼                            ▼
 java.lang.Long  ───────► java.math.BigInteger
      ▲                            │
      └──── compact when safe ◄────┘
```

This preserves Python arbitrary-precision behavior while avoiding `BigInteger`
allocation for ordinary integer arithmetic. A later optimization tier can
lower proven-safe hot locals all the way to primitive JVM `long` values.

## Architecture

```text
Python 3.15 source
       │
       ▼
    ast.parse
       │
       ▼
semantic lowering
       │
       ├── fast JVM-local path for ordinary functions
       └── chained lexical environments only when closures need them
       │
       ▼
JVM instruction builder
       │
       ▼
direct class-file writer
       │
       ▼
     .class
       │
       ▼
small PyRuntime support layer
```

Generated classes and the support runtime target **Java 21 only** (major version
65), with verifier frames emitted as `StackMapTable` attributes. Java 21 or newer
is required to build and run them. Earlier target values fail explicitly.
See [JVM_BACKEND.md](JVM_BACKEND.md) for frame analysis and validation details.

## Differential tests

```bash
pyjvm315-conformance tests/conformance --python python3.15
```

Machine-readable output and unsupported-feature grouping are available with:

```bash
pyjvm315-conformance tests/conformance --python python3.15 --json
pyjvm315-conformance tests/conformance --python python3.15 --unsupported-summary
```

The bundled focused corpus currently executes **38 differential cases**. In the current development environment all 38 pass, with 0 unsupported and 0 failed. The reference executable available here is CPython 3.13, so final Python 3.15 conformance still needs to be rerun against an actual `python3.15` binary.

## Build and run

```bash
python -m pip install -e .
pyjvm315 examples/hello.py -o build/jvm -c Hello
java -cp build/jvm Hello
```

`javac` is currently used only to compile the small `PyRuntime.java` support
class. Python user code itself is emitted directly as JVM bytecode.

## Important current limitations

This is still far from full CPython compatibility. Major missing areas include:

- remaining generator corners: abrupt `return`/`break`/`continue` through a *yielding* `finally` suite and exact edge cases around `GeneratorExit`
- async generators/coroutines and `async for` / `async with`
- star imports, namespace packages, import hooks/loaders, and the full import protocol
- descriptors, properties, metaclasses, `super()`, `__slots__`, complete special-method dispatch
- real traceback/frame objects, exact exception formatting, and the complete builtin exception hierarchy
- deletion of local/global names and exact unbound-local sentinel behavior on the fast local path
- bytes/bytearray/memoryview and many builtin types
- full Unicode/string behavior and complete container APIs
- standard-library compatibility and native/extension-module strategy

## Direction toward `Lib/test`

The next work should prioritize features based on failure frequency when running
curated CPython 3.15 `Lib/test` files. The immediate high-value tranche is:

1. real traceback/frame objects plus more exact exception formatting/chaining;
2. descriptors, properties, `super()`, special-method dispatch, and richer class behavior;
3. `async def`, `await`, async iterators/context managers, and async generators;
4. stronger module/package/import semantics including star imports and import hooks;
5. more builtin types/APIs and enough standard-library shims to run real `test_*.py` modules;
6. begin importing curated CPython 3.15 `Lib/test` cases and prioritize by unsupported/failure frequency.

Once compatibility is substantial, performance becomes a first-class gate:
pyjvm315-generated bytecode should be benchmarked against equivalent direct Java
bytecode, with the goal that compiler-provable hot paths stay close to Java/JVM
performance rather than paying generic Python runtime costs unnecessarily.


## 0.6 generator delegation and exception fidelity

Version 0.6 adds real `yield from` delegation on top of the suspended generator
state machine. Delegated `send()` values are forwarded into subgenerators and a
subgenerator's `return` value becomes the value of the `yield from` expression.
Delegation to ordinary iterators is also supported.

Caught exceptions are now represented as Python-like exception values instead of
only their message payload. This enables `e.args` and `StopIteration.value` in
`except ... as e` handlers. Generator execution also implements PEP 479's core
rule: a `StopIteration` raised by generator code is translated to `RuntimeError`,
while normal generator completion continues to use `StopIteration.value`.

Still pending in the generator/exception area: full `generator.throw()`, cleanup
semantics for `close()`/`GeneratorExit`, `yield` inside all `try/finally` shapes,
exception `__cause__`/`__context__`/tracebacks, async generators, and coroutines.
## 0.7 packages, explicit exception causes, and generator injection

Version 0.7 expands the compatibility surface in three high-value areas:

- package-aware compilation with `__init__.py` discovery;
- absolute and relative local-package imports, including `from .helper import name`;
- generated `__name__` and `__package__` module globals;
- dotted-package imports such as `import pkg.helper`;
- explicit exception chaining with `raise X(...) from cause` and `raise X(...) from None`;
- exception `__cause__` and `__suppress_context__`;
- generator `.throw()` for uncaught injected exceptions;
- generator `.close()` using injected `GeneratorExit`;
- fresh-generator `.throw()` semantics and terminal state after uncaught generator errors.

Generator exception injection is now represented as a pending Python exception on
the suspended generator frame. Each ordinary `yield` resumes through a checkpoint
that either returns the sent value or raises the pending exception. This is the
right foundation for compiling `yield` inside `try` blocks in the next tranche.

Remaining generator work includes catching `.throw()` inside generator-side
`try/except`, `finally` execution during close, delegation of `throw()`/`close()`
through `yield from`, and full `GeneratorExit` corner cases.



## 0.8 generator unwinding, delegation, and scalable conformance

Version 0.8 closes several generator-control-flow gaps:

- `.throw()` can now be caught by `try/except` inside a suspended generator;
- `.close()` injects `GeneratorExit` through generator-side `finally` cleanup;
- `yield from` forwards `throw()` and closes delegated generators before propagating `GeneratorExit`;
- generator `return`, `break`, and `continue` execute non-yielding `finally` suites before completing the transfer;
- implicit exception `__context__` is populated when a new exception is raised while handling another exception.

The conformance runner now builds the Java support runtime once per suite rather than once per test file, and multi-file cases treat helper modules / package initializers as dependencies. This removes a large fixed cost and makes larger CPython-regression batches practical.

Current intentionally explicit generator limitations are `yield` *inside* a `finally` suite and a bare re-raise after suspension inside an `except` handler. Both require persisting cleanup/active-exception continuations across suspension rather than relying on ordinary JVM locals.


## 0.9 suspended cleanup, decorators, and expression coverage

Version 0.9 removes several compatibility restrictions that were especially
visible in generator and resource-management code:

- active exceptions in generator `except` handlers are persisted across suspension, so a bare `raise` after `yield` re-raises the correct exception;
- a generator `finally` suite may itself contain `yield`; normal and exceptional entry share one persisted cleanup state instead of duplicating yield labels;
- generator `with` statements preserve their context manager across suspension and route injected exceptions through `__exit__`;
- ordinary `with` statements now run normal `__exit__` cleanup before `return`, `break`, and `continue`, including nested cleanup propagation;
- general function decorators are evaluated in source order and applied bottom-to-top through normal Python callable dispatch;
- generator `for ... else` and `while ... else` use separate exhaustion and break targets;
- assignment expressions are supported, including Python's containing-scope binding rule for assignment expressions inside comprehensions.

The focused differential corpus is 38/38 passing in the current development
environment. A deliberately explicit remaining corner is abrupt
`return`/`break`/`continue` through a *yielding* generator `finally`; that case
needs a persisted pending-control-transfer record in addition to the pending
exception record now implemented.

## 0.10 class model and CPython test probing

Version 0.10 extends user-defined classes with class attributes, `@property`
getters/setters, zero-argument and explicit `super()`, `@classmethod`,
`@staticmethod`, and broader special-method dispatch. User classes can now
participate in `str`/`repr`, truth testing, `len`, arithmetic/comparison,
indexing, membership, and iterator protocol through their dunder methods.

A new `pyjvm315-probe` command provides a compile-coverage lane for a real
CPython checkout. Point it at `Lib/test` to group the remaining unsupported
compiler features by frequency before attempting full differential execution:

```bash
pyjvm315-probe /path/to/cpython/Lib/test --pattern 'test_*.py'
pyjvm315-probe /path/to/cpython/Lib/test --json > probe.json
```

The bundled focused differential corpus is now 41/41 passing in the current
development environment.

## 0.11 method binding and descriptor protocol

Version 0.11 unifies Python argument binding for compiled functions and user-defined
methods. Instance methods, class methods, static methods, and `__init__` now support
positional-only parameters, defaults, keyword-only parameters, `*args`, `**kwargs`,
keyword calls, and ordered keyword mappings through the same runtime binder.

The user-defined class runtime also implements the core descriptor protocol for class
attributes whose values define `__get__`, `__set__`, or `__delete__`. Data descriptors
precede instance fields, non-data descriptors can be shadowed by instance fields, class
access invokes `__get__(None, owner)`, and missing instance attributes can fall back to
`__getattr__`.

The focused differential suite now contains 43 passing programs, including new cases
derived from CPython's `test_call.py` method-binding behavior and descriptor semantics.

## 0.12 logical frames and Python-visible tracebacks

Version 0.12 adds a Python-level frame/traceback model that is independent of JVM
reflection frames. Compiled calls maintain a lightweight logical frame stack; generated
statements update the current Python source line, and exceptions build traceback chains
from those logical frames as they propagate.

Exception objects now expose `__traceback__`; traceback objects expose `tb_next`,
`tb_frame`, and `tb_lineno`; frames expose `f_code` and `f_locals`; and code objects
expose `co_name`, `co_filename`, and `co_firstlineno`. Ordinary functions and generators
are both covered. Nested-function traceback order, first-line metadata, and active line
numbers match the reference CPython cases in the focused differential corpus.

The focused suite now contains 46 passing executable conformance programs.

## 0.13 live frame locals, class decorators, and coroutine groundwork

Version 0.13 makes traceback frame locals useful: compiled argument bindings and
subsequent name assignments are mirrored into the active logical Python frame, and
traceback `f_locals` retains the same mapping so updates made while handling an
exception remain visible.

General class decorators are evaluated in source order and applied bottom-to-top,
matching function decorator ordering. Explicit `metaclass=type` is accepted as the
first metaclass compatibility step; custom metaclass namespace/call semantics remain a
future tranche.

The compiler now accepts `async def` for top-level, nested, and user-defined instance
methods. Calling an async function creates a lazy coroutine object; `.send(None)`,
`close()`, `__await__()`, nested compiled `await`, return values, exceptions, keyword
arguments, and async instance methods are supported. This first coroutine backend runs
compiled awaits synchronously to completion; event-loop suspension, async generators,
`async for`, and `async with` are intentionally not claimed yet.

The focused differential corpus contains 51 executable cases and remains green in
batched validation against the available CPython reference.

## 0.14 async iteration and async context managers

Version 0.14 extends the coroutine groundwork with Python's async iteration and context
manager protocols. Compiled code now supports `async for`, loop `else`, `async with`,
`aiter()`, `anext()`, `__aiter__`, `__anext__`, `__aenter__`, `__aexit__`, and
`StopAsyncIteration`. Return/break/continue cleanup through async context managers uses
the same structured unwinding machinery as regular `with` and `try/finally`.

The tranche also closes two general compatibility gaps found by the async cases:
augmented assignment now supports attribute and subscript targets while evaluating the
container/object only once, and exception classes can be raised directly (for example
`raise ValueError`) rather than requiring an explicitly-created exception instance.

The focused differential corpus now contains 57 executable cases. All cases pass in
batched validation against the available CPython reference, including the multi-file
import/package cases.

## 0.15 suspendable coroutine state machine

Version 0.15 moves explicit `await` points onto the same persistent JVM state-machine
backend used by generators. Async functions without suspension points retain the cheaper
direct coroutine path; functions containing `await` preserve locals and program counters
in a persistent environment and can yield control back to their caller.

Coroutine `.send()`, `.throw()`, `.close()`, and `__await__()` now forward through
nested awaited coroutines and custom awaitables. A custom `__await__` iterator may yield
an external scheduling token, receive a value back on resume, return a final value, or
receive an exception injected at the suspended await point.

This tranche also introduces repository-native compatibility CI with Java 21 and the
currently available CPython 3.14 runner. The focused corpus contains 61 focused executable
conformance cases at this milestone. The compiler still parses source with the Python
3.15 AST feature level; a dedicated 3.15 CI lane will be added when setup-python exposes
3.15 on the hosted runner.

## 0.16 async generators and strict compatibility gating

Version 0.16 adds a distinct async-generator runtime model backed by the existing
persistent suspension frame machinery. Async generator functions now return async
generator objects rather than coroutines, and support `__aiter__()`, `__anext__()`,
`asend()`, `athrow()`, and `aclose()` through per-operation awaitable objects.

The compiler also generalizes suspended expression lowering so partially evaluated
binary operations and collection literals are persisted across JVM resumes rather than
depending on the JVM operand stack. This closes previous gaps such as `return await x`,
arithmetic around an await, and lists containing multiple await expressions.

The focused compatibility gate is now strict: both semantic failures and unsupported
cases fail CI. The 0.16 corpus passes 65/65 executable differential cases with
0 unsupported and 0 failed. `await` inside an async generator remains explicitly
unsupported until scheduler suspensions and user-visible async-generator yields are
tagged separately.

## 0.17 await inside async generators

Version 0.17 separates the two kinds of suspension that occur inside an async generator.
Source-level async-generator `yield` values are wrapped in an internal item tag, while
values yielded by an internal `await` remain scheduler suspension tokens. The
`__anext__` / `asend` / `athrow` operation awaitables therefore complete only when a
tagged generator item appears; scheduler tokens are yielded outward and later resume
values or injected exceptions are forwarded back into the suspended async-generator
frame.

This enables `await` inside compiled async-generator bodies without confusing scheduler
coordination values with items produced to an `async for` consumer. The same persistent
frame backend continues to own locals, program counters, cleanup state, and exception
injection.

The strict focused differential corpus passes 67/67 executable cases with 0 unsupported
and 0 failed.

## 0.18 suspendable async-for and async generator expressions

Version 0.18 lowers `async for` into explicit suspendable `await anext(...)` states
instead of synchronously draining the asynchronous iterator protocol. Loop `else`
continues to distinguish normal iterator exhaustion from a user `break` through a
persisted exhaustion flag.

Async instance/class/static methods that can suspend now use the same persistent
coroutine-frame backend as top-level async functions. This closes the previous gap where
an async method such as `__anext__` could accidentally drain an internal await
synchronously.

Async generator expressions are compiled as real async-generator frames with eager
evaluation of the outer iterable expression and lazy asynchronous element production.
The strict focused differential corpus passes 69/69 executable cases with
0 unsupported and 0 failed.

## 0.19 suspendable async comprehensions

Version 0.19 adds list, set, and dict async comprehensions using synthetic suspendable
helper coroutines. The helpers capture the enclosing lexical environment, execute mixed
sync/async comprehension clauses through the ordinary async-for lowering, and return the
completed container through normal coroutine completion.

Suspension is supported both in the asynchronous iterator protocol and in call
expressions within comprehension elements. Suspended method calls now persist the bound
object, positional argument list, and keyword mapping across resumes and invoke through
the runtime's dynamic method-dispatch path.

The strict focused differential corpus passes 71/71 executable cases with
0 unsupported and 0 failed.

## 0.20 suspendable async-with cleanup continuations

Version 0.20 makes async context-manager entry and exit fully suspendable. `async with`
is lowered into explicit awaited `__aenter__` / `__aexit__` control flow with correct
exception suppression and normal-exit behavior.

The suspended frame backend now carries an ordered pending control transfer through
yielding cleanup suites. A cleanup may suspend while preserving a pending return value,
break target, continue target, exception, or normal completion, then resume the original
transfer after cleanup finishes. Mixed nested cleanup ordering is preserved.

This also removes the older generator limitation on `return` / `break` /
`continue` through a `finally` suite that itself yields.

The strict focused differential corpus passes 77/77 executable cases with
0 unsupported and 0 failed.

## 0.21 Python class creation hooks

Version 0.21 implements the next layer of Python's class creation protocol. `__new__`
is implicitly static and participates in allocation before `__init__`; a non-instance
result from `__new__` correctly skips `__init__`. `object.__new__(cls)` is available
as the primitive allocator.

Class finalization now follows PEP 487 ordering: class attributes whose values define
`__set_name__` receive the final owner and attribute name before the inherited
`__init_subclass__` hook is invoked.

The runtime also exposes class/instance creation metadata including `__class__`,
`__dict__`, `__name__`, `__qualname__`, `__module__`, `__bases__`, and
`__mro__`, and explicit `class C(object)` bases are supported.

The strict focused differential corpus passes 80/80 executable cases with
0 unsupported and 0 failed.

## 0.22 custom metaclass protocol and class keywords

Version 0.22 adds explicit and inherited custom metaclass selection, including
`__prepare__`, metaclass `__new__`, `__init__`, and `__call__`. Prepared namespace
mappings are populated with compiled class attributes/methods and handed to the
metaclass creation hooks; namespace mutations made by metaclass `__new__` become
attributes on the resulting class.

`type.__new__`, `type.__init__`, and `type.__call__` are available to compiled
metaclass implementations, and custom metaclasses are visible through `type(C)` /
`C.__class__`. Metaclass selection propagates through inheritance and reports
conflicts between incompatible metaclasses.

Named class declaration keywords are now evaluated once and forwarded through
`__prepare__`, metaclass `__new__` / `__init__`, and the default
`__init_subclass__` path.

The strict focused differential corpus passes 83/83 executable cases with
0 unsupported and 0 failed.

## 0.23 __slots__ and member descriptors

Version 0.23 implements class slot layouts during class finalization. Declared slot
names become data descriptors in the class dictionary and instance slot values are
stored separately from the ordinary instance dictionary.

Classes with `__slots__` and no `__dict__` reject undeclared instance attributes and
do not expose `instance.__dict__`. Uninitialized and deleted slots raise
`AttributeError`; explicit `"__dict__"` in `__slots__` restores dynamic attributes.

Slot descriptors participate in normal MRO/data-descriptor lookup. Slotted base-class
members remain available in subclasses, while a subclass that omits `__slots__`
receives a normal instance dictionary as in CPython. Slot/class-variable conflicts are
detected during class creation.

The strict focused differential corpus passes 88/88 executable cases with
0 unsupported and 0 failed.

## 0.24 private name mangling and slot validation

Version 0.24 aligns compiler-side class-private name mangling with the slot runtime.
Private class attributes, methods, properties, and syntactic attribute references such
as `self.__value` now use the same mangled key as private `__slots__` descriptors.
Leading underscores in class names follow CPython's rule, including the all-underscore
class-name case where mangling is suppressed.

Slot declarations now validate names as identifiers in addition to requiring strings.
Invalid names such as whitespace-containing strings are rejected with `TypeError`,
while single-string slot declarations continue to represent one slot rather than a
sequence of characters.

The strict focused differential corpus passes 95/95 executable cases with
0 unsupported and 0 failed.

## 0.25 star imports and module export analysis

Version 0.25 adds local-module `from module import *` lowering without introducing a
second dynamic namespace model. The multi-module compiler statically derives each local
module's export set: a literal `__all__` is authoritative, otherwise public top-level
bindings are exported. Public names propagated by another star import are included
recursively.

Star-imported names are pre-registered as ordinary module globals before function
bytecode is emitted, so functions and closures can reference them through the same
generated static-field path used by explicit imports. Relative package star imports and
package `__init__.py` re-exports are supported. Dynamic/non-literal `__all__` remains
an explicit compile-time limitation rather than silently using incorrect exports.

The strict focused differential corpus passes 100/100 executable cases with
0 unsupported and 0 failed.

## 0.26 bytes, bytearray, and memoryview basics

Version 0.26 adds a shared binary-sequence runtime model for immutable `bytes`, mutable
`bytearray`, and basic `memoryview` objects. Bytes literals are emitted directly from
the Python AST, and the three builtin constructors participate in normal Python type
introspection.

Binary sequences integrate with the existing generic protocols: `len`, iteration,
integer indexing, slicing, containment, equality, truth testing, concatenation, and
repetition. Bytearray and writable memoryview support integer item assignment, and a
memoryview over bytearray writes through to the original storage.

The initial method surface includes string `encode`, bytes/bytearray `decode` and
`hex`, bytearray `append` / `extend`, and memoryview `tobytes`, `tolist`, and
`readonly`. Full slice assignment and the complete buffer-format/casting API remain
future work.

The strict focused differential corpus passes 105/105 executable cases with
0 unsupported and 0 failed.

## 0.27 Python-visible module metadata

Version 0.27 makes compiled local modules expose normal Python-facing metadata instead
of requiring callers to know about generated JVM classes. Imported modules now receive
generated `__file__`, `__loader__`, and `__spec__` globals alongside `__name__`
and `__package__`. A lightweight compiled loader and ModuleSpec-like object expose
`name`, `parent`, `origin`, `loader`, and package
`submodule_search_locations`.

Module wrappers also expose a Python-visible `__dict__` containing generated globals,
functions, classes, and package child modules. Direct-file `__main__` execution now
matches CPython's `__package__ is None` and `__spec__ is None` behavior while retaining
a real `__file__`.

The strict focused differential corpus passes 109/109 executable cases with
0 unsupported and 0 failed.

## 0.28 binary sequence API and bytearray slice assignment

Version 0.28 expands the shared bytes/bytearray implementation using one common binary
search/splitting layer. Both types now support `find`, `count`, `startswith`,
`endswith`, `replace`, `split`, and `join`, while preserving the receiver's
immutable or mutable result type where Python does.

Bytearray now supports slice assignment. Contiguous slice replacement may resize the
array, including insertion through an empty slice. Extended slices preserve length and
raise `ValueError` when the replacement size does not match the selected positions.

The strict focused differential corpus passes 113/113 executable cases with
0 unsupported and 0 failed.

## 0.29 CPython-driven binary search and bytearray mutators

Version 0.29 is prioritized directly from method frequencies in CPython 3.15's
`Lib/test/test_bytes.py`. Bytes and bytearray now support reverse search/indexing and
partitioning through `rfind`, `index`, `rindex`, `partition`, and `rpartition`.

Bytearray gains the high-frequency mutable-sequence operations `clear`, `copy`,
`insert`, `pop`, `remove`, and `reverse`, including Python-compatible missing-value
and empty-pop errors.

The strict focused differential corpus passes 120/120 executable cases with
0 unsupported and 0 failed.

## 0.30 CPython-driven binary text API

Version 0.30 continues to follow method frequencies in CPython 3.15's
`Lib/test/test_bytes.py`. Bytes and bytearray now support `translate` with optional
deletion, and `bytes.maketrans` / `bytearray.maketrans` produce 256-byte translation
tables through normal builtin-type method dispatch.

Both binary types also support `strip`, `lstrip`, `rstrip`, and ASCII
`lower`, `upper`, `capitalize`, `title`, and `swapcase`, while preserving the
receiver's immutable or mutable result type.

The strict focused differential corpus passes 126/126 executable cases with
0 unsupported and 0 failed.

## 0.31 CPython 3.15 regression probing

Version 0.31 adds a persistent case-level regression-probe lane against selected CPython
3.15 tests. Unlike the older whole-file probe, it extracts individual `test_*`
functions/methods, neutralizes external imports into placeholder bindings, compiles each
case independently, and aggregates unsupported/compiler-error reasons by frequency.

CI checks out CPython's `3.15` branch, probes a pinned manifest covering bytes,
descriptors, and importlib import behavior, and publishes the full JSON report as the
`cpython-regression-probe` artifact. Probe findings are reporting data; the existing
focused differential suite remains the strict merge gate.

The initial baseline covers 358 CPython cases:

- 125 compile
- 231 are explicitly unsupported
- 2 reach unexpected compiler errors

The dominant unsupported feature is nested/local class definitions inside functions
(154 cases), followed by missing core builtins / builtin exception classes and the
current four-argument dynamic method-call ceiling. See `CPYTHON_REGRESSION_PROBE.md`
for the recorded baseline and priority order.

The focused differential corpus remains 126/126 passing with 0 unsupported and 0 failed.


## Performance benchmarks

The first reproducible performance tranche compares emitted bytecode with
hand-written Java for bounded integer arithmetic, range/while loops, function
calls, and list growth/indexing:

```bash
python benchmarks/run.py
```

It reports generated/Java, runtime-Java/Java, and generated/runtime-Java ratios,
checks every result against CPython, and retains warmed multi-fork samples plus
bytecode disassembly. The runtime control distinguishes current dynamic
semantics/representation costs from codegen overhead; it is not a claim that
those costs are unavoidable. A separate benchmark workflow compares the PR
compiler with its base on the same runner and catches large regressions.
See [benchmarks/README.md](benchmarks/README.md) for bounds, timing controls,
interpretation, reproducibility, and gate policy. This tranche changes no
compiler, runtime, or nested/local-class compatibility behavior.

## CPython compatibility after backend modernization

The pinned 358-case compile probe now reaches **325 compiling cases (90.8%)**,
33 unsupported, and **zero compiler errors**, up from 125 compiling / 231
unsupported / 2 errors. Local class methods/properties capture their enclosing
function and a distinct class cell for each creation; registration now traverses
control-flow suites. The tranche also adds ord/chr, lazy map, first-class
builtin calls, exception hierarchy coverage, unrestricted call arity, expression
bases/metaclasses and JVM modified UTF-8 constants.

The focused differential suite is **199 passing cases**. A new strict execution
lane runs **125 selected CPython test executions** using unchanged test bodies and
explicit fixtures, without placeholder imports. CI runs those against a Python
3.15 reference and Java 21. Compile coverage remains a triage measure, separate
from runtime test success. See [CPYTHON_REGRESSION_PROBE.md](CPYTHON_REGRESSION_PROBE.md)
for the pinned source, measurements, current limitations and next priorities.


## Java 21 and builtin compatibility

The backend now keeps only target 21 and always emits verifier frames. Name
and captured-cell deletion distinguishes unbound bindings from None; exception
handler names are cleared on abrupt exits too. Top-level class methods now use
the same closure lowering as local-class methods, enabling nested definitions,
lambdas and comprehensions in methods.

First-class builtin aliases include hash, id, len, iter/next, reversed,
attribute helpers and pow. Numeric hash values use the 64-bit CPython modulus;
bytes/Unicode use SipHash13 and honor numeric `PYTHONHASHSEED`. Differential
runs default that seed to 0 for reproducibility. The runtime handles
NotImplemented fallback for addition/subtraction/multiplication/power and
equality, modern boolean rejection, slices, in-place list/bytearray operations,
and bytes constructor index callbacks. These are supported paths, not full
builtin, buffer-protocol or dict/set key-protocol compatibility.


## Complex-number compatibility

Complex literals and the first-class `complex` constructor now support numeric
and string inputs, keyword real/imag parts and conversion callbacks. Arithmetic
uses CPython 3.15 mixed real/complex rules, including signed zero, scaled division,
infinity recovery, integer powers, conjugation and numeric hash consistency.
Five unchanged CPython complex test bodies join the strict execution lane;
three differential programs exercise arithmetic, construction and hash/equality.

Complex subclasses, complete builtin numeric descriptors, constructor warnings,
all nonfinite exponent cases, and exact float-to-string parity across the entire
binary64 range remain work in progress. Dict/set key protocols still use Java
containers. These checks extend supported paths without establishing full
CPython compatibility.


## First-class descriptor compatibility

Class members now execute in source order in a class namespace. Earlier methods
can be aliased, wrapped with `classmethod`/`staticmethod`/`property`, or replaced
by later assignments. Defaults and decorators read that namespace; method,
lambda and comprehension bodies retain Python's lexical rules and class-cell
captures. Missing global bindings use an explicit unbound state.

Descriptors support instance/class binding, subclass owners, class-bound
`super`, callable static methods, property getter/setter/deleter copies and
function metadata. Instance fields can override non-data descriptors, while
properties keep data-descriptor precedence. Four unchanged CPython descriptor
test bodies join the strict execution lane.

Descriptor subclasses, full builtin callable metadata and live class mapping
proxies remain unfinished. Unannotated functions expose their empty annotations; access to
unimplemented deferred annotations on annotated functions raises
`NotImplementedError`, rather than returning an empty placeholder.


## Class-body statement compatibility

Class suites now support nested class definitions, methods inside control-flow
suites, loops, unpacking/chained assignments, deletion, exception handling and
context managers using the same ordered namespace. Global and nonlocal
bindings bypass that namespace; nested methods retain their enclosing function
closures and distinct class cells. Exception aliases are removed from the
namespace after handling, including abrupt exits. Loop branches run context
cleanup only when they leave that context.

The unchanged CPython `test_compattr` body now runs in the execution lane,
covering a nested custom descriptor with private getter/setter/deleter methods.
Annotation-only assignments remain explicitly unsupported until deferred
annotation semantics are implemented. Compile coverage is not full CPython
runtime compatibility.


## Ordered builtin compatibility

`min` and `max` support an iterable or multiple positional values, keyword-only
`key` and `default`, single-pass iteration and first-winner ties. `sorted`
supports a cached key per item, stable reverse ordering and Python rich
comparisons. It consumes its iterable before validating sort keywords and
converting `reverse` to a boolean, matching the Python 3.15 reference. These
builtins work through aliases and class namespaces; builtin function objects
retain their identity across lookups.

Ordering handles reflected methods and NotImplemented fallback, NaNs, exact
mixed integer/float comparisons, Unicode code points, byte sequences and
lexicographic tuple/list comparisons. `bin`, `oct` and `hex` support arbitrary
integers and the `__index__` protocol. Six more unchanged CPython bodies pass,
including five from `test_builtin.py`; the execution corpus now extends beyond
the five-file compile probe. Stable merge sorting does not reproduce CPython's
Timsort comparison trace for stateful or inconsistent comparison methods.


## Super protocol compatibility

`super` is a first-class constructor with zero, one or two positional arguments.
Unbound objects bind as non-data descriptors; bound objects retain their receiver,
owner and starting class. Cooperative subclasses, readonly `__thisclass__`,
`__self__` and `__self_class__`, explicit reinitialization and supported
object/type/super MRO boundaries have executable differential coverage.
Metaclass descriptors bind the class receiver and keep data-descriptor precedence.

Zero-argument calls use the current function's first positional argument and its
actual class-cell context. Nested functions, lambdas, generators, captured
argument changes and deleted/empty bindings follow the Python 3.15 reference.
Class suites have isolated logical frames and pop them on errors; generator
expressions use their implicit iterable argument. Seven unchanged bodies from
CPython's `test_super.py` join the strict execution lane. The complete `test_descr.test_supers` body now passes with the percent-formatting
tranche below.
Class-cell propagation and validation are covered below. Custom super attribute
overrides and copying/pickling remain unfinished.


## Class-cell construction protocol

The compiler publishes the actual implicit `__classcell__` to a metaclass only
when a method or class lambda needs it. `type.__new__` fills that shared cell
before returning, so methods can use `__class__` during metaclass construction.
Missing propagation and a cell populated with a different class raise errors;
non-class metaclass results can retain an empty cell for later construction.
Class decorators run after validation. Reusing a namespace in three-argument
`type` construction creates distinct classes and updates the shared cell.

Function `__closure__` exposes cached tuples of shared lexical cells, with
read/write/delete `cell_contents` and an explicit empty-cell error. Empty local
bindings are reserved before nested definitions can inspect them. Five additional
unchanged CPython bodies cover construction timing, delayed creation, namespace
cell identity, wrong-cell rejection and a closure cell used as a super receiver.
The focused corpus now has 168 cases and the strict lane has 61 executions.
This does not complete closure/code-object introspection, custom metaclass `mro`
behavior, full three-argument `type` metaclass selection, or native-module support.


## Percent string formatting

String `%` formatting supports `%s`, `%r`, `%a`, `%c`, decimal/octal/hex integer
conversions and `%e`/`%f`/`%g` (including uppercase forms). It handles tuple or
mapping operands, balanced mapping keys, flags, dynamic width/precision, Unicode
codepoint widths/truncation, escaped percent signs and ignored `h`/`l`/`L` prefixes.
Bound and first-class `str.__mod__` use the same implementation; `%=` preserves
normal string rebinding. Display and numeric conversions invoke Python protocols
and propagate callback failures. Missing mapping keys retain their `KeyError`
argument and representation.

Finite float formatting rounds the exact binary64 value with half-even decimal
rounding; general formatting switches notation after rounding. Signed zero,
subnormal values, exponent padding, alternate forms and nonfinite values have
CPython 3.15 differential coverage. The runtime keeps this formatting parser off
the shared method dispatcher. The corpus reaches **172 differential cases** and
**62 unchanged CPython executions**, including the complete `test_supers` body.

This tranche does not implement bytes `%`, str/numeric subclass hooks, the `format`
builtin, brace formatting or f-string format specifications. Width/precision are
limited by Java's int-sized string storage; extreme allocation behavior and all
CPython formatting diagnostics are not fully reproduced. Compilation coverage
remains 319/358; it is separate from runtime and full-suite compatibility.


## Format protocol and f-string specifications

`format(value, spec)` and f-string fields share a runtime formatter. Special
`__format__` lookup uses the value's type, binds descriptors, supports inherited
methods/metaclasses, and requires a string result. It bypasses instance attributes;
ordinary direct `value.__format__` access retains normal attribute lookup. Object
formatting accepts an empty spec and rejects unsupported nonempty specifications.
Builtin descriptors and first-class format aliases are covered.

Strings support Unicode fill/alignment, width and precision. Integers support
base/character conversion, signs, alternate prefixes, zero padding and grouping.
Floats support fixed/scientific/general/percent presentation, signs, grouping,
rounded negative-zero coercion and shortest round-trip output. F-strings now honor
nested dynamic fields, `!s`/`!r`/`!a`, debug fields and callback evaluation order;
specifications were previously ignored.

The differential corpus reaches **176 cases**; the unchanged CPython lane retains
**62 passing executions**. The fixed probe gains `AssortedBytesTest.test_format`
(compilation only), reaching 320/358; `test_special_method_lookup` now reaches its
next missing builtin, `dir`. Full format-spec support, fractional grouping, locale-
aware `n`, complex formatting, string/numeric subclasses, brace `.format()` parsing,
extreme allocation parity and external modules remain unfinished. Java 21 remains
the only target.


## Namespace inspection and sequence descriptors

`dir(value)` binds type-level `__dir__` descriptors, supports inherited methods and
metaclass hooks, consumes their iterables and sorts the results without removing
callback duplicates. Its default user-class view combines inherited class members,
slots and instance fields. `vars(value)` returns the actual instance/function
attribute dictionary and honors custom `__dict__` descriptors and attribute access.

In ordinary functions, `dir()` lists current bindings and `vars()` returns a fresh
snapshot, including referenced closure cells and excluding declared globals.
Class-suite inspection uses the existing live class namespace. Descriptor-backed
`__getitem__` now supplies lazy sequence iteration when `__iter__` is absent,
stopping at IndexError/StopIteration and propagating other callback exceptions.

Four differential programs bring the corpus to **180 passing cases**. The full,
unchanged CPython property test joins the execution lane: **50 bodies / 63 passing
executions**. The pinned compile probe reaches **323/358 (90.2%)**, with **35
unsupported** and zero compiler errors; CI requires at least 323 compiling cases.
The other gains, `test_dir` and `SharedKeyTests.test_subclasses`, are compilation
only; module-subclass and CPython dictionary-memory-layout semantics are unfinished.

Native `dir` member lists remain partial. Module-level and suspended-frame zero-
argument inspection, live module dictionaries and readonly/live class dictionary
views remain explicitly unsupported. Full proxy, builtin and dynamic-execution
semantics still need work; this tranche does not establish full CPython support.


## Round protocol compatibility

`round(number, ndigits)` supports positional and keyword binding, first-class
aliases and type-level `__round__` lookup with descriptor/metaclass binding.
Instance attributes do not override special lookup. Omitted or None `ndigits`
call custom methods without an argument; other values pass through unchanged.
Builtin int/bool/float `__round__` descriptors are also available.

Integers round decimal positions with exact half-even arithmetic, preserving
arbitrary precision. Floats round their exact binary64 value with half-even
semantics, preserve signed zero when `ndigits` is provided and return integers
when it is omitted. Index descriptors supply `ndigits`; extreme float precision
is clipped before allocation, and nonfinite/inexact-overflow cases raise the
corresponding Python errors.

Three differential programs bring the focused corpus to **183 passing cases**.
The complete unchanged CPython `BuiltinTest.test_round` body joins the strict
execution lane, which now passes **51 bodies / 64 executions**. The fixed compile
probe reaches **324/358 (90.5%)**, **34 unsupported** and zero compiler errors.
`test_special_method_lookup` gains compilation only; its external module and
other protocol dependencies still prevent a full runtime claim. Native numeric
subclasses, full diagnostic fidelity and allocation-heavy extremes remain
unfinished. Java 21 and the benchmark regression gates remain in place.


## Frozen sets and set algebra

`frozenset` is an immutable, hashable native collection. Construction validates
member hashes, preserves numeric key equality (including int/bool/float/complex),
reuses existing exact frozen sets and caches the order-independent CPython hash.
Frozen sets can be nested or used as dictionary keys. Mutable sets now use the
same Python hash/equality keys instead of Java element equality.

Both types support union, intersection, difference, symmetric difference, subset/
superset/disjoint checks and mixed set operators; the left operand determines the
result type. Methods accept iterables where Python does, while operators require
sets. Mutable methods remain unavailable on frozenset. Bound collection methods,
native descriptors, copying and conversion of mutable membership probes are covered.

Three differential programs bring the corpus to **186 passing cases**. Four complete
unchanged CPython `TestJointOps` bodies run against explicit set/frozenset setup
fixtures, adding eight executions: **55 bodies / 72 passing executions**. The fixed
probe reaches **325/358 (90.8%)**, **33 unsupported** and zero compiler errors.
The class-assignment case gains compilation only; native collection subclasses,
CPython memory/layout behavior, complete mutation/iterator/diagnostic parity and
external modules remain unfinished. Java 21 remains the sole target.


## Mutable set augmentation

Mutable set `|=`, `&=`, `^=` and `-=` preserve object identity, so aliases and
attribute/subscript targets observe the mutation. Frozen operands retain immutable
result behavior. Augmented dispatch binds type-level in-place descriptors and
falls back through ordinary/reflected operations when they return NotImplemented;
instance attributes do not override the in-place protocol. Native in-place set
methods return NotImplemented for incompatible operands.

`difference_update` now preserves removals made before an iterable callback or
unhashable member fails. Operations between native sets reuse cached member hashes;
mutation commits preserve those keys instead of calling member hashes again.

Three differential programs bring the corpus to **189 passing cases**. Five full,
unchanged CPython mutable-set bodies join the execution lane: **60 bodies / 77
passing executions**. The fixed compile probe remains **325/358**, 33 unsupported
and zero compiler errors. This improves runtime semantics without claiming new
compile coverage. Native collection subclasses, full mutation/iterator/diagnostic
parity, dynamic execution and external/native modules remain unfinished.


## Set iterator lifetime and mutation

Set and frozenset iterators expose `__iter__`, `__next__` and
`__length_hint__`. A size change raises `RuntimeError` with the Python diagnostic
and remains invalid even if the original size is restored later. Length hints
reflect remaining elements without advancing or invalidating the iterator;
exhaustion remains permanent after later mutations.

Same-size clear/refill and no-op augmented operations avoid Java structural
modification errors. Normal traversal stays linear, with cursor recovery only
when a same-size structural mutation occurs. Order across mutations is unspecified;
this does not reproduce CPython's hash-table layout or concurrent/reentrant mutation
behavior. Deletion and pop retain cached member hashes.

Three differential programs bring the corpus to **192 passing cases**. Five more
unchanged CPython bodies bring the strict execution lane to **65 bodies / 82 passing
executions**, including the clear/refill iterator regression and deletion/pop tests.
Compilation remains **325/358**, 33 unsupported and zero compiler errors. Java 21
and performance regression gates remain in place; full CPython compatibility is
still unfinished.


## Pinned 3,000-body CPython compatibility target

The selected compilation corpus now contains **3,000 explicit test-body identities
from 47 files**, pinned to CPython 3.15 commit
`5b28ebd109f08cfc44a3d6e573e087eaf86319b5`. It retains all original 358 bodies and
adds core builtins, numeric/string/collection operations, shared collection mixins,
iteration, generators/coroutines, exceptions, scoping, assignment expressions,
context managers, pattern matching and formatting. The final math file contributes
its first 21 bodies; all preceding files contribute every discoverable body.

| Evidence | Current result |
|---|---:|
| Selected bodies compiling independently | **2,373 / 3,000 (79.1%)** |
| Explicitly unsupported selected bodies | **627** |
| Unexpected compiler errors | **0** |
| Unchanged CPython bodies verified at runtime | **108 bodies / 125 executions** |
| Project differential cases | **199 passing** |

Compilation neutralizes module-global dependencies and removes case decorators;
it does not execute fixtures, imports or assertions. These are selected source
bodies, including mixins, rather than CPython's dynamically discovered execution
cases. **The 79.1% figure is compilation coverage of this selection, not overall
CPython compatibility.** The strict runtime lane is a separate, much smaller set.

The first expanded scan found 2,208 compiling, 769 unsupported and 23 compiler
errors. Initializing non-argument JVM locals to the unbound sentinel and recognizing
assignment-expression bindings removes all 23 verifier failures and gains another
16 previously unsupported bodies. Skipped bindings raise UnboundLocalError;
initialization does not invent a Python value. Nested/local-class closure work
and Java 21 targeting remain intact.

CI checks exactly 3,000 unique pinned identities, corpus/manifest fingerprints,
zero compiler errors and a 2,373 compilation floor. A per-case baseline also rejects
any previously compiling body that regresses, even if another body improves. The
legacy 358-body probe remains at 325 compiling / 33 unsupported, with its existing
gate retained. New runtime bodies cover assignment expressions and lexical
unbound errors without placeholder bindings or fixture changes.

Run the expanded gate with Python 3.15:

```sh
python -m pyjvm315.regression_probe \
  --cpython-root /path/to/cpython \
  --manifest tools/cpython_probe_3000_manifest.txt \
  --baseline tools/cpython_probe_3000_baseline.json \
  --expect-cases 3000 --min-compiles 2373 --fail-on-error \
  --output artifacts/cpython-regression-probe-3000.json
```

Sequence patterns (76 unsupported bodies), mapping patterns (36) and class
patterns (35) remain. Dynamic execution (`exec`, `eval`, `compile`), external
modules and other missing builtins also remain. Baseline updates must record deliberate progress or corpus changes;
they must not hide regressions. The benchmark regression gates remain enabled.


## Scalar pattern matching

`match` now supports literal/value patterns, singleton identity (`None`, `True`,
`False`), capture/wildcard patterns, OR alternatives, nested `as` bindings and
guards. The subject is evaluated once. Captures are installed before the guard;
a false guard moves to the next case, while comparison/guard exceptions propagate.
Literal comparison follows Python equality; singleton patterns use identity.

Capture names participate in module/class/function/global/nonlocal scopes and
module exports. Case-local function/class definitions retain per-creation closures
and class cells. Existing return/break/continue cleanup applies to case bodies.
Invalid OR binding sets, duplicate captures and unreachable irrefutable cases are
rejected. Sequence, mapping and class patterns, plus suspension inside match, are
explicitly unsupported in this tranche.

The 3,000-body corpus gains **126 compiling bodies**: **2,373 compiling / 627
unsupported / zero compiler errors**. CI raises the floor and per-case baseline
without changing any selected identity. Forty complete unchanged TestPatma bodies
join the strict lane, with no fixture changes: **108 bodies / 125 passing executions**.
Four differential programs bring the project corpus to **199 passing cases**,
including side effects, closure/private/global/nonlocal scopes, cleanup and exports.
Compilation coverage is distinct from runtime compatibility. Java 21 and the
benchmark gates remain enabled.
