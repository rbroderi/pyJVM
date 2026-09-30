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

Class-file version 49 is still emitted intentionally so the initial backend does
not require `StackMapTable` generation. Modern JVMs can load these classes.

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
- JVM stack-map generation and modern selectable class targets

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
