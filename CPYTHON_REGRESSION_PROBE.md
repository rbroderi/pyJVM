# CPython 3.15 Regression Probe Baseline

This report records the first case-level pyJVM compatibility probe against a curated
subset of the CPython 3.15 regression suite.

## Probe set

- `Lib/test/test_bytes.py`
- `Lib/test/test_descr.py`
- `Lib/test/test_importlib/import_/test_api.py`
- `Lib/test/test_importlib/import_/test_fromlist.py`
- `Lib/test/test_importlib/import_/test_relative_imports.py`

The probe extracts individual `test_*` functions/methods, neutralizes external imports
into placeholder bindings, and compiles each case independently. This is a compile
coverage/triage signal, not a claim that the CPython test itself executes successfully.

## Initial 0.31 baseline

| Status | Cases |
| --- | ---: |
| Compiles | 125 |
| Unsupported | 231 |
| Compiler error | 2 |
| Total | 358 |

## Highest-frequency unsupported reasons

| Count | Reason |
| ---: | --- |
| 154 | nested/local class definitions are not implemented |
| 12 | `ord` is not available |
| 8 | dynamic method calls currently support up to 4 arguments |
| 5 | `map` is not available |
| 5 | `ImportError` is not available |
| 4 | `MemoryError` is not available |
| 4 | `BufferError` is not available |
| 3 | `hash` is not available |
| 3 | `BytesWarning` is not available |
| 3 | `ModuleNotFoundError` is not available |

The remaining unsupported reasons are mostly single- or low-frequency builtin names,
external modules, and a few language/runtime gaps.

## Remaining compiler errors

Only two cases still reach unexpected compiler exceptions in this initial probe:

- one `IndexError: pop from empty list`
- one internal `KeyError` keyed by an AST/object identity

These should be treated as compiler robustness bugs rather than ordinary unsupported
features.

## Priority implied by the probe

1. Register/lower nested class definitions inside functions.
2. Remove the arbitrary 4-argument dynamic method-call ceiling.
3. Add the high-frequency core builtins and builtin exception classes surfaced here.
4. Re-run the same pinned manifest and measure movement from the 125/358 baseline.
5. Expand the manifest only after the current high-frequency gaps stop dominating it.

CI publishes the full machine-readable report as the `cpython-regression-probe`
artifact on every pull request and push to `main`.

## Compatibility tranche after JVM modernization

Both the compile probe and executable CPython lane now pin the corpus to
CPython 3.15 commit `5b28ebd109f08cfc44a3d6e573e087eaf86319b5`.
The same five-file, 358-case probe now reports:

| Status | Initial | Local-class tranche | Java 21 / builtin tranche | Complex tranche | Descriptor tranche | Class-body tranche | Ordered-builtin tranche |
|---|---:|---:|---:|---:|---:|---:|---:|
| Compiles | 125 | 264 | 299 | 303 | 312 | 315 | 317 |
| Unsupported | 231 | 94 | 59 | 55 | 46 | 43 | 41 |
| Compiler error | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| Total | 358 | 358 | 358 | 358 | 358 | 358 | 358 |

Compile coverage increased from 34.9% to 88.5% (+192 cases; +2 this tranche). The probe now
attempts local classes rather than pre-rejecting them. Neither passing compilation
nor neutralized external imports establish that a CPython test passes at runtime.

Implemented changes include local-class lexical closures and per-creation class
cells, closure-aware methods/properties, definitions inside control-flow suites,
expression bases/metaclasses, unrestricted dynamic call arity, ord/chr and lazy
map, first-class builtin calls, added exception hierarchy entries, class
docstrings, and correct modified UTF-8 constants. Generator-expression compilation
preserves enclosing control stacks; async lowering preserves nested definition
identities. The two original unexpected compiler errors are eliminated.

### Executable evidence

The focused differential corpus is now 161 cases. A separate
`pyjvm315.cpython_execution` lane executes 36 unchanged CPython test
bodies (49 executions): shared BaseBytesTest bodies run with bytes and bytearray,
while seven ByteArrayTest bodies use only bytearray. Five ComplexTest bodies use
a numeric fixture with assertion helpers and no substituted module globals. Six
ClassPropertiesAndMethods bodies and five BuiltinTest/TestSorted bodies use an
objects fixture with assertions only. Explicit fixtures implement
`type2test`, `assertEqual`, `assertNotEqual`, `assertIs`, and callable/context-manager `assertRaises`. It uses no placeholder imports or None
bindings, and rejects a failing CPython reference run. The initial executions
all pass locally. Hosted CI uses actual CPython 3.15 (prerelease allowed), Java
21, strict JVM verification and publishes the execution report. Local validation also uses CPython 3.15.0rc2; the report records the actual
oracle version and corpus commit. The full focused CI lane now uses 3.15 too,
including the modern ValueError behavior for arbitrarily large chr arguments.

```bash
python -m pyjvm315.cpython_execution --cpython-root /path/to/cpython \
  --manifest tools/cpython_execution_manifest.txt --target 21 \
  --python python3.15 --output artifacts/cpython-execution.json
```

The fixtures cover only these selected bodies; this is not full unittest,
standard-library, or full CPython regression-suite compatibility. Decorated
cases are rejected by this lane rather than stripping skip/platform semantics.

### Remaining priorities toward full compatibility

The largest remaining probe blockers are dynamic eval/exec, missing core builtins and external imports. External imports still include
copy, weakref, binascii and CPython native test modules such as `_testcapi`.

The next tranches should implement these semantics with executable differential
cases, expand the real execution manifest and fixtures, then broaden coverage
beyond the current five probe files. Native-extension tests need an explicit
support strategy. The remaining 41 cases remain visible as unsupported; no
placeholder implementation or skip is counted as full compatibility.

Compiler errors and compilation coverage below 317 now fail the pinned probe
CI job (`--fail-on-error --min-compiles 317`). The old 0.31
priority list above remains a historical record, not the current work order.


### Java 21 and builtin tranche

Java 21 is now the sole generated/runtime target; older targets are rejected,
and verifier-frame generation is unconditional. Module/local/nonlocal deletion
uses explicit unbound states rather than conflating deletion with None. Closure
cells remain assignable after deletion; imported-module attributes and dict
views omit deleted bindings. Exception-handler targets clear through return,
raise, yield and close cleanup paths.

All class methods now share function/closure lowering, fixing nested definitions,
lambdas and comprehensions in top-level methods. Context managers receive an
exception instance rather than its raw message. Added executable paths cover
builtin aliases, callable/sentinel iteration, next defaults, lazy reversed,
modular power, slices, weak identity IDs, and hash(). Numeric hashes and seeded
SipHash13 match the 64-bit CPython reference; numeric PYTHONHASHSEED is honored,
and the differential runner defaults it to 0. NotImplemented falls through
reflected arithmetic/equality protocols and rejects boolean use. In-place
list/bytearray operations preserve identity, and bytes constructors invoke
index callbacks. The new execution fixtures reject missing/wrong exceptions.

This does not complete the full builtin/buffer/import model. Hash() support does
not yet replace Java-backed dict/set key protocols; general writable-buffer
export tracking and native-extension compatibility remain unfinished. Compile
coverage and the 33 selected executions stay distinct from full-suite support.


### Complex tranche

The pinned five-file probe gains four compiling cases (299 to 303), with no
compiler errors. Imaginary literals, complex construction/aliases, numeric
conversion callbacks, real/imag attributes, conjugation, arithmetic, equality
and 64-bit numeric hashing now have executable coverage. Mixed arithmetic uses
real/complex rules to preserve signed zero and avoid unnecessary NaNs; division
uses scaling, multiplication recovers infinities, and small integer powers use
repeated squaring. Focused tests also exercise malformed strings, overflow and
unsupported ordering/floor division/modulo/conversion exception types.

The strict lane adds unchanged `ComplexTest.test_conjugate`, `test_hash`,
`test_neg`, `test_floordiv` and `test_mod` from the same pinned corpus. Platform-
decorated tests remain rejected; signed-zero representations are tested in the
focused suite. Complex subclasses, full numeric descriptors, constructor warning
behavior, all nonfinite exponent cases and exact binary64 string formatting
remain unfinished. The compile probe is still distinct from these 38 executions
and from full CPython-suite support.


### First-class descriptor tranche

The same pinned probe reaches 312 compiling cases (87.2%), 46 unsupported and
zero compiler errors. Class bodies use an ordered namespace instead of separate
attribute/method/property emission passes. Function descriptors and first-class
classmethod/staticmethod/property objects support aliases, normal decorator
application, inherited binding, property copies and data-descriptor precedence.
Class-bound super(), callable static wrappers, rebinding and metadata have
focused differential coverage. Class names mask closure lookup when Python
requires global fallback; comprehensions evaluate only their first iterable in
the class namespace. Global fields start unbound to distinguish forward reads
from a binding to None.

Four unchanged CPython bodies are added: `test_methods`, `test_staticmethods`,
`test_classmethod_without_dict_access` and
`test_staticmethod_annotations_without_dict_access`. The execution lane now
covers 29 bodies / 42 passing executions; the focused suite has 154 cases.
Unannotated-function annotations are supported, but annotated-function deferred
annotation evaluation raises an explicit NotImplementedError. At the end of this
tranche, general class-body control flow/nested class statements, descriptor
subclassing, complete builtin callable metadata and live class dict proxies
remained unfinished. Compile
coverage is still distinct from runtime and full-suite compatibility.


### Class-body statement tranche

The pinned probe reaches **315 compiling cases (88.0%)**, 43 unsupported and
zero compiler errors. Nested classes and methods in control-flow suites are
registered in their own lexical scopes and keep separate class cells. Class
suites lower supported statements through their ordered namespace, including
loop targets, unpacking/chained assignments, deletion, context managers and
exception-handler cleanup. Global and nonlocal declarations apply across the
suite and bypass class namespace bindings. Declared global fields are allocated
before module initialization so forward reads raise NameError.

Three new focused programs cover namespace order, closure masking, repeated
nested-class creation, zero-argument super, branch cleanup, context-manager
suppression and class-creation failures. They also exposed and fixed premature
with/finally cleanup on branches inside nested loops. The differential corpus
has **157 passing cases**; the unchanged CPython `test_compattr` body brings the
strict lane to **30 bodies / 43 passing executions**. Annotation-only assignments
are rejected explicitly rather than producing a binding to None. Deferred
annotations, builtin descriptor metadata and the external dependencies remain
unfinished; this does not establish full CPython regression-suite support.


### Ordered-builtin tranche

The fixed five-file probe reaches **317/358 (88.5%)**, 41 unsupported and zero
compiler errors. `test_properties_plus` and `test_basic_inheritance` now compile;
compilation alone remains distinct from runtime success. The strict execution
lane adds unchanged `test_properties_plus`, `BuiltinTest.test_hex`, `test_oct`,
`test_bin`, and `TestSorted.test_bad_arguments`/`test_baddecorator`. This expands
execution evidence to **36 bodies / 49 passing executions**, including the
additional `test_builtin.py` source. No module globals or imports are replaced.
The new `assertHasAttr` helper checks the actual attribute and has a negative
fixture test to prevent false passes.

Four focused programs bring the differential suite to **161 passing cases**.
They cover extrema signatures, defaults, keys, first-winner ties, generator
consumption, callable keys, errors, stable sorting, rich comparison reflection,
NaNs, integer/float precision, Unicode/sequence ordering and integer base
formatting through `__index__`. Builtin function objects are cached so repeated
lookups preserve identity. Sorted keys run after input consumption; reverse
truth conversion occurs between those phases, as in Python 3.15.

Sorting uses stable merge sorting with only less-than comparisons, not a Java
Comparator contract. It does not reproduce CPython Timsort's exact comparison
trace or every result for stateful/inconsistent orderings. Deferred annotations,
dynamic execution, external/native modules and remaining builtin semantics
still need implementation and broader runtime evidence.
