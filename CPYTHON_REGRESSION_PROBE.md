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

| Tranche | Compiles | Unsupported | Compiler errors |
|---|---:|---:|---:|
| Initial | 125 | 231 | 2 |
| Local classes | 264 | 94 | 0 |
| Java 21 / builtins | 299 | 59 | 0 |
| Complex | 303 | 55 | 0 |
| Descriptors | 312 | 46 | 0 |
| Class bodies | 315 | 43 | 0 |
| Ordered builtins | 317 | 41 | 0 |
| Super | 319 | 39 | 0 |
| Format | 320 | 38 | 0 |
| Introspection | 323 | 35 | 0 |
| Round | 324 | 34 | 0 |
| Frozen sets | 325 | 33 | 0 |

Compile coverage increased from 34.9% to 90.8% (+200 cases; unchanged this runtime tranche). The probe now
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

The focused differential corpus is now 189 cases. A separate
`pyjvm315.cpython_execution` lane executes 60 unchanged CPython test
bodies (77 executions): shared BaseBytesTest bodies run with bytes and bytearray,
while seven ByteArrayTest bodies use only bytearray. Five ComplexTest bodies use
a numeric fixture with assertion helpers and no substituted module globals. Eight
ClassPropertiesAndMethods bodies, six BuiltinTest/TestSorted bodies and twelve
TestSuper bodies use an objects fixture with assertions only. Four TestJointOps bodies
run with set/frozenset constructor and word/dictionary setup fixtures (eight executions). Five TestSet bodies run with the same set setup fixture. Explicit fixtures implement
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
support strategy. The remaining 33 cases remain visible as unsupported; no
placeholder implementation or skip is counted as full compatibility.

Compiler errors and compilation coverage below 325 now fail the pinned probe
CI job (`--fail-on-error --min-compiles 325`). The old 0.31
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


### Super protocol tranche

The pinned probe reaches **319/358 (89.1%)**, 39 unsupported and zero compiler
errors. `test_metaclass` and `test_supers` now compile. These counts do not
establish runtime success: the unchanged `test_supers` execution body still
requires `%` string formatting and is not counted as passing.

Four new focused programs bring the differential corpus to **165 cases**.
They exercise first-class constructors, unbound descriptor binding, cooperative
subclasses, readonly metadata, repeated initialization, class/instance receivers,
object/type/super MRO boundaries and metaclass data-descriptor precedence.
Zero-argument calls use a recorded first argument and class-cell context;
argument environments preserve nonlocal changes, and resumed generators restore
that context. Generator expressions use their implicit iterable argument.
Class suites use isolated logical frames with exception-safe cleanup, including
literal-only suites, verified under Java 21.

Seven unchanged TestSuper bodies are added: `test___class___instancemethod`,
`test___class___classmethod`, `test___class___staticmethod`, `test_shadowed_local`,
`test_super___class__`, `test_super_subclass___class__` and `test_super_init_leaks`.
The execution lane now covers **43 bodies / 56 passing executions**, with no
substituted imports or globals. Executing the last body validates repeated
initialization but does not replace CPython's dedicated reference-leak runner.
Metaclass class-cell propagation/validation, custom super attribute overrides,
copying/pickling, string formatting and external/native modules still need work.

The benchmark gate caught a 4.84x collection regression in this tranche. The
shared runtime dispatcher had grown to 8,275 bytes and JDK 21 PrintCompilation
showed it stayed uncompiled. Splitting builtin-type handling into a helper
reduced it to 7,211 bytes and restored tier-3/tier-4 compilation. The final
head/base ratios were 0.95 arithmetic, 0.97 range loop, 1.00 calls and 1.02
collections, with all checksums and the regression gate passing. Both generated
and runtime-control timings exposed the dispatch cost; it was not counted as
unavoidable Python semantics overhead.


### Class-cell construction tranche

The fixed compile probe remains **319/358**, with **39 unsupported** and **zero
compiler errors**. Runtime evidence grows to **168 differential cases**,
**48 unchanged CPython bodies / 61 executions**, and **40 unit tests**.

Five new unchanged `TestSuper` bodies pass: `test___class___new`,
`test___class___delayed`, `test___classcell___expected_behaviour`,
`test___classcell___wrong_cell` and `test_cell_as_self`. The assertion-only
objects fixture gains `assertNotIn`, with a negative fixture check; no test body,
import or module global is substituted. The missing-cell and overwritten-cell
error cases run in focused programs; their original CPython bodies still require
unsupported regex/subtest fixture facilities and are not counted as passing.

Class construction publishes a shared cell, populates it during `type.__new__`,
and validates it after metaclass construction and before decorators. A metaclass
returning a non-class leaves its cell available for delayed construction. Three-
argument `type` creates a fresh class even when a prepared namespace is reused.
The created class retains the namespace snapshot used by `type.__new__`, rather
than being overwritten again after the metaclass returns. Namespace copying and
zero-argument `object()` are supported for these paths.

Three focused programs cover cell identity and mutation, early and delayed
access, missing/invalid/wrong cells, copied and reused namespaces, class decorators,
local-class isolation, local `__class__` shadowing, and empty captured bindings.
They do not establish full cell construction/equality, code-object introspection,
transitive free-variable introspection, custom metaclass MRO computation or full
metaclass selection for three-argument `type`. Those and the previously listed
formatting, dynamic-execution, external/native-module gaps remain unfinished.


### Percent-formatting tranche

The fixed compile probe stays **319/358**, **39 unsupported**, and **zero compiler
errors**. Runtime coverage reaches **172 passing differential programs** and
**49 unchanged CPython bodies / 62 executions**. `ClassPropertiesAndMethods.test_supers`
now passes as a complete unchanged body, covering cooperative multiple inheritance,
super subclasses, unbound descriptor binding, properties, classmethods and errors.
The assertion fixture and existing nested/local-class lowering are unchanged.

Four new focused programs cover percent text/ASCII/character conversion, arbitrarily
large integer output and numeric callbacks, binary64 float rounding/notation, and
mapping/error behavior. Matrices include Unicode width/truncation, dynamic fields,
flags, alternate forms, tuple argument counts, missing keys, negative zero, subnormals,
maximum finite binary64, infinities, NaNs, conversion callback exceptions and Python
3.15 argument-number/key diagnostics. Direct and first-class `str.__mod__` and `%=`
share the parser. Floating-point conversion rounds the exact binary64 value using
half-even decimal arithmetic, rather than Java Formatter's separate conventions.

Bytes percent formatting, numeric/string subclass hooks, brace/format/f-string
specifications, Java-sized storage limits and extreme allocation behavior remain
unfinished. All remaining compile blockers stay visible; this is a runtime semantics
tranche and does not establish full CPython regression-suite support.


### Format protocol tranche

The fixed probe reaches **320/358 (89.4%)**, **38 unsupported** and **zero compiler
errors**. `AssortedBytesTest.test_format` now compiles; this does not establish its
runtime success, since its unchanged body requires regex/module fixtures. The
missing format builtin no longer blocks `test_special_method_lookup`, which now
stops at `dir`. The CI compile floor rises to 320 without changing the manifest.

Four new differential programs bring the corpus to **176 cases**. They cover
format special lookup versus ordinary attribute access, descriptors/inheritance,
metaclass formatting, callbacks, string/integer/float specifications, grouping,
Unicode width/fill, rounded zero coercion and dynamic f-string fields. F-string
specifications now execute through the same protocol instead of being ignored.
The 49 unchanged CPython bodies / 62 executions and 40 unit tests remain passing.
No unsupported regex fixture or class-subclass dependency is neutralized to claim
that the entire CPython `BuiltinTest.test_format` body passes.

Float display selects the shortest decimal that round-trips to binary64, with
Python notation boundaries and signed-zero handling; precision-based formatting
uses the existing exact-value half-even rounding. This does not complete fractional
grouping, locale-specific `n`, complex formatting, str/numeric subclasses, brace
parsing or extreme allocation behavior. Existing dynamic-execution, introspection,
external/native-module and full-suite gaps remain visible.


### Introspection and sequence-descriptor tranche

The fixed five-file probe reaches **323/358 (90.2%)**, **35 unsupported** and
**zero compiler errors**. `test_properties`, `test_dir` and
`SharedKeyTests.test_subclasses` gain compilation; special-method lookup now stops
at the next missing builtin, `round`. Compilation alone does not establish runtime
success or CPython-specific dictionary memory-layout compatibility.

Four differential programs cover type-level `__dir__` lookup, descriptors,
metaclasses, sorted callback results, instance/function `vars` identity and mutation,
custom dictionary access, function snapshots, closure cells, live class namespaces
and descriptor-backed sequence iteration. They bring the focused corpus to **180**.
The complete unchanged `ClassPropertiesAndMethods.test_properties` body now passes
with assertion-only fixtures (`assertIn` and `fail` added), bringing the real lane
to **50 bodies / 63 executions**. No imports or test statements are replaced.

Native directory inventories remain partial. Module-level/suspended-frame
zero-argument inspection, live module dictionaries and readonly/live class
mapping views raise explicit NotImplementedError. Existing dynamic execution,
module subclasses, native extensions and full-suite gaps remain visible. The CI
compile floor rises to 323; Java 21 and the benchmark regression gates remain.


### Round protocol tranche

The fixed compile probe reaches **324/358 (90.5%)**, **34 unsupported** and **zero
compiler errors**. `ClassPropertiesAndMethods.test_special_method_lookup` now
compiles; its external module/protocol dependencies remain unfinished, so this is
not a claim that its complete body passes at runtime. The CI compile floor is 324.

Three differential programs bring the corpus to **183 passing cases**. They cover
custom/inherited/described/metaclass round dispatch, instance-attribute bypass,
keyword binding, native descriptors, arbitrary-precision integer ties, binary64
rounding boundaries, signed zero, nonfinite inputs, overflow and index descriptors.
The full unchanged `BuiltinTest.test_round` body now passes with the existing
assertion-only fixture: **51 bodies / 64 executions**. No test statements or module
imports are replaced, and decorated `test_round_large` remains outside the strict
manifest; its large integral-float boundary is covered by differential inputs.

Native numeric subclasses, exact diagnostic parity and allocation-heavy extremes
remain unfinished. This tranche does not complete CPython compatibility; dynamic
execution, namespace views, external modules and native extensions remain visible.


### Frozen sets and set-algebra tranche

The fixed compile probe reaches **325/358 (90.8%)**, **33 unsupported** and **zero
compiler errors**. The class-assignment case previously blocked by `frozenset`
now compiles; it still depends on unfinished native class/layout semantics, so no
runtime success is claimed. CI requires at least 325 compiling cases.

Three differential programs cover immutable construction, hashability and cached
hashes, numeric key equivalence, nesting, dictionary keys, bound/native descriptors,
mutable membership probes, mixed algebra and comparisons, iterable methods,
mutability rejection and callback exceptions. The focused corpus reaches **186**.
Four unchanged `TestJointOps` bodies (`test_len`, `test_contains`, `test_equality`,
`test_setOfFrozensets`) run with set and frozenset setup fixtures. Their statements
and imports are unchanged: the strict lane reaches **55 bodies / 72 executions**.
The fixture supplies the original word/letters/constructor values and a dictionary
with the same keys/None values, without substituting unsupported module imports.

Native collection subclasses, CPython storage/layout, complete mutation/iteration
and diagnostic parity remain unfinished. Dynamic execution, namespace views,
external modules and native extensions remain visible; compile coverage is still
separate from full runtime regression-suite compatibility.


### Mutable-set augmentation tranche

Compile coverage remains **325/358**, **33 unsupported** and **zero compiler
errors**; the CI floor stays 325. This tranche fixes runtime aliasing and exception
state rather than adding names solely to increase compilation counts.

Three differential programs bring the focused corpus to **189 passing cases**.
They cover mutable identity versus frozen replacement, self-operations, attribute/
subscript aliases, in-place descriptors, instance-attribute bypass, NotImplemented
and reflected fallback, native descriptor signatures, partial difference-update
failures and reuse of cached member hashes. Five complete unchanged `TestSet`
bodies (`test_ior`, `test_iand`, `test_isub`, `test_ixor`, `test_inplace_on_self`)
join the existing setup fixture, bringing the strict lane to **60 bodies / 77
executions**. No test statements/imports or module fixtures are neutralized.

Native collection subclasses and complete mutation/iterator/diagnostic semantics
remain unfinished, alongside dynamic execution, namespace views and external/native
modules. Java 21 and the benchmark regression gates remain in place.
