# N1 baseline: N0 ticket 13 gate results (B1)

B1 = `68a5099` (N0 ticket 13 commit, also origin `main`). The ticket 13 gates were run on the tree `f9f2de8` plus
comment- and documentation-only edits (the ticket 13 diff: docs, class comments, regenerated `STDLIB_STATUS.md` and
manifest), i.e. equivalent to B1; they were not rerun at B1. Sources: HANDOFF.md, entry "2026-11-24: N0 ticket 13 ...
done"; WSL logs `~/pyjvm-work/logs/pyjvm-n0-f9f2de8/` (`conf313.log`, `conf314.log`, `conf315.log`, `pytest.log`,
`methodsize.log`, `strict.log`); Windows logs `E:\pyjvm-work\n0\logs\t13-wfull-*.log`. Case names below were extracted
from those logs. Compare later gates by case name against this file.

Hosts: Linux = WSL Ubuntu 24.04, glibc 2.39, kernel 6.6.87.2, x86-64, reference lanes 3.13.18 / 3.14.10 / 3.15.2.
Windows = Windows 11, venvs `E:\pyjvm\.venv313/314/315`.

## Totals

| Gate | Linux (WSL) | Windows |
| --- | --- | --- |
| pytest (`tests/`) | 149 passed, 3 skipped, 0 failed | 134 passed, 18 skipped, 0 failed (Linux-only tests skip) |
| methodsize `--runtime` | 0 of 4895 methods over 8000 bytes | 0 of 4895 |
| conformance 3.15 | 399 passed, 2 failed, 3 skipped | 401 passed, 0 failed, 3 skipped |
| conformance 3.14 | 385 passed, 1 failed, 18 skipped | 386 passed, 0 failed, 18 skipped |
| conformance 3.13 | 375 passed, 2 failed, 27 skipped | 377 passed, 0 failed, 27 skipped |
| strict lane | 808 PASS, 0 UNSUPPORTED, 0 FAIL, 0 ERROR (32 units) | not run (Linux only) |

Each conformance lane has 404 cases (passed + failed + skipped). "Unsupported" is 0 everywhere.

## Failing conformance cases (Linux only; Windows has none)

| Lane | Failing case | Note |
| --- | --- | --- |
| 3.15 | `time_struct_time_and_strftime` | stdout differs; also fails on 3.14 and 3.13 (same case on all three Linux lanes) |
| 3.15 | `zlib_streams` | stdout differs; passes on 3.14 and 3.13 |
| 3.14 | `time_struct_time_and_strftime` | stdout differs |
| 3.13 | `time_struct_time_and_strftime` | stdout differs |
| 3.13 | `threading_imports` | stdout differs; flaky (passed at N0 ticket 5, a baseline failure at ticket 13, not rerun) |

HANDOFF's ticket 13 entry states the Linux failing sets equal the earlier N0 baseline. The extra passes on
Windows (3.13: 377 vs 375; 3.15: 401 vs 399; 3.14: 386 vs 385) are exactly the cases above.

## Skipped conformance cases (version-gated; identical lists on Linux and Windows)

The runner's reason is "requires python >= X" or "< X".


### 3.13 (27)
- annotations_deferred_class.py (>= 3.14)
- annotations_deferred_conditional.py (>= 3.14)
- annotations_deferred_exec.py (>= 3.14)
- annotations_deferred_function.py (>= 3.14)
- annotations_deferred_function_attrs.py (>= 3.14)
- annotations_deferred_future.py (>= 3.14)
- annotations_deferred_syntax.py (>= 3.14)
- array_315_codes.py (>= 3.15)
- ast_compare.py (>= 3.14)
- ast_feature_version_314.py (>= 3.14)
- binascii_base64_315.py (>= 3.15)
- complex_arithmetic.py (>= 3.15)
- complex_constructor.py (>= 3.15)
- format_bytes_percent.py (>= 3.15)
- format_cpython_port.py (>= 3.15)
- format_frac_grouping.py (>= 3.15)
- format_percent.py (>= 3.15)
- frozendict_basic.py (>= 3.15)
- hash_string_kw_315.py (>= 3.15)
- io_codecs_basic.py (>= 3.14)
- io_memory.py (>= 3.14)
- lazy_import_basic.py (>= 3.15)
- not_implemented.py (>= 3.15)
- percent_mapping_errors.py (>= 3.15)
- support_helpers.py (>= 3.15)
- typeparams_sentinel.py (>= 3.15)
- zlib_315.py (>= 3.15)

### 3.14 (18)
- annotations_eager.py (< 3.14)
- annotations_eager_function.py (< 3.14)
- array_315_codes.py (>= 3.15)
- binascii_base64_315.py (>= 3.15)
- complex_arithmetic.py (>= 3.15)
- complex_constructor.py (>= 3.15)
- format_bytes_percent.py (>= 3.15)
- format_cpython_port.py (>= 3.15)
- format_frac_grouping.py (>= 3.15)
- format_percent.py (>= 3.15)
- frozendict_basic.py (>= 3.15)
- hash_string_kw_315.py (>= 3.15)
- lazy_import_basic.py (>= 3.15)
- not_implemented.py (>= 3.15)
- percent_mapping_errors.py (>= 3.15)
- support_helpers.py (>= 3.15)
- typeparams_sentinel.py (>= 3.15)
- zlib_315.py (>= 3.15)

### 3.15 (3)
- annotations_eager.py (< 3.14)
- annotations_eager_function.py (< 3.14)
- lazy_import_identifier.py (< 3.15)

## pytest skips and not-recorded items

- The pytest logs are in dots-and-counts form: the names of the 3 (Linux) and 18 (Windows) skipped tests were not
  recorded and the gate was not rerun. The Windows count is larger because the Linux-only tests skip there.
- Windows has no strict lane and no `hs_err` files were produced (HANDOFF).
- Nothing in this baseline comes from a run at B1 itself; see the top of this file.
