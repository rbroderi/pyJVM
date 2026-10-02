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

| Status | Initial | Current |
|---|---:|---:|
| Compiles | 125 | 264 |
| Unsupported | 231 | 94 |
| Compiler error | 2 | 0 |
| Total | 358 | 358 |

Compile coverage increased from 34.9% to 73.7% (+139 cases). The probe now
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

The focused differential corpus is now 139 cases. A separate
`pyjvm315.cpython_execution` lane executes nine unchanged CPython `BaseBytesTest`
method bodies for both bytes and bytearray (18 executions), with explicit
`type2test` and `assertEqual` fixtures. It uses no placeholder imports or None
bindings, and rejects a failing CPython reference run. The initial executions
all pass locally. Hosted CI uses actual CPython 3.15 (prerelease allowed), Java
21, strict JVM verification and publishes the execution report. Local validation
uses the available Python 3.12 reference; the report records the actual oracle
version and corpus commit.

```bash
python -m pyjvm315.cpython_execution --cpython-root /path/to/cpython \
  --manifest tools/cpython_execution_manifest.txt --target 21 \
  --python python3.15 --output artifacts/cpython-execution.json
```

The fixtures cover only these selected bodies; this is not full unittest,
standard-library, or full CPython regression-suite compatibility. Decorated
cases are rejected by this lane rather than stripping skip/platform semantics.

### Remaining priorities toward full compatibility

The largest remaining probe blockers are `hash` and name deletion (8 each),
followed by NotImplemented, id, slice, eval, complex, first-class method
wrappers, and general class-body statements. External imports still include
copy, weakref, binascii and CPython native test modules such as `_testcapi`.

The next tranches should implement these semantics with executable differential
cases, expand the real execution manifest and fixtures, then broaden coverage
beyond the current five probe files. Native-extension tests need an explicit
support strategy. The remaining 94 cases remain visible as unsupported; no
placeholder implementation or skip is counted as full compatibility.

Compiler errors and compilation coverage below 264 now fail the pinned probe
CI job (`--fail-on-error --min-compiles 264`). The old 0.31
priority list above remains a historical record, not the current work order.
