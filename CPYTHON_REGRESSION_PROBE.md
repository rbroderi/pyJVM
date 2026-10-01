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

## Baseline

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
