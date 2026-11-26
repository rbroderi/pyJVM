# N2 baseline: N1 ticket 12 gate results (B2)

B2 = `7c9010f` (tip of `n1-proc`, local only; the N2 branch `n2` is cut from it). The ticket 12 gates were **not run at
`7c9010f` itself**: they ran at `25d8e3b` (N1 ticket 11) plus the ticket 12 working-tree diff, which `git diff
25d8e3b 7c9010f` shows to be documentation and comment text only (HANDOFF.md, JVM_BACKEND.md, STDLIB_STATUS.md and its
manifest/generator line, comment-only edits in `PosixFiles`, `SignalModule`, `StubModules`, two plan files); the review
nits were applied after the gates. Sources: HANDOFF.md, entry "2026-11-25: N1 ticket 12 (documentation and final gates)
done"; WSL logs `~/pyjvm-work/logs/pyjvm-n0-25d8e3b/` (`conf313.log`, `conf314.log`, `conf315.log`, `pytest.log`,
`methodsize.log`, `strict.log`); Windows logs `E:\pyjvm-work\n1\logs\t12\` (`t12-wfull-pytest.log`,
`t12-wfull-conf313/314/315.log`). Case names below were read from those logs (the conformance logs name only SKIP and
FAIL cases; passing cases are counted, not named). Compare later gates by case name against this file.

Hosts: Linux = WSL Ubuntu 24.04, glibc 2.39, kernel 6.6.87.2, x86-64, reference lanes 3.13.18 / 3.14.10 / 3.15.2.
Windows = Windows 11, venvs `E:\pyjvm\.venv313/314/315`.

## Totals

| Gate | Linux (WSL) | Windows |
| --- | --- | --- |
| pytest (`tests/`) | 165 passed, 3 skipped, 0 failed | 134 passed, 34 skipped, 0 failed (Linux-only tests skip) |
| methodsize `--runtime` | 0 of 5041 methods over 8000 bytes | not recorded at ticket 12 (W-min runs it) |
| conformance 3.15 | 407 passed, 2 failed, 3 skipped | 409 passed, 0 failed, 3 skipped |
| conformance 3.14 | 393 passed, 1 failed, 18 skipped | 394 passed, 0 failed, 18 skipped |
| conformance 3.13 | 384 passed, 1 failed, 27 skipped | 385 passed, 0 failed, 27 skipped |
| strict lane | 808 PASS, 0 UNSUPPORTED, 0 FAIL, 0 ERROR (32 units) | not run (Linux only) |

Each conformance lane has 412 cases (passed + failed + skipped). "Unsupported" is 0 everywhere.

## Failing conformance cases (Linux only; Windows has none)

| Lane | Failing case | Note |
| --- | --- | --- |
| 3.15 | `time_struct_time_and_strftime` | stdout differs (timezone name `GMT` vs `UTC`); same case fails on 3.14 and 3.13 |
| 3.15 | `zlib_streams` | stdout differs; passes on 3.14 and 3.13 |
| 3.14 | `time_struct_time_and_strftime` | stdout differs |
| 3.13 | `time_struct_time_and_strftime` | stdout differs |

The N0 baseline's flaky `threading_imports` (3.13) passed in this run. The extra Windows passes (3.15: 409 vs 407,
3.14: 394 vs 393, 3.13: 385 vs 384) are exactly the failing cases above.

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

- The pytest logs are in dots-and-counts form: the names of the 3 (Linux) and 34 (Windows) skipped tests were not
  recorded and the gate was not rerun. The Windows count is larger because the Linux-only tests (N0 and N1 POSIX
  tests) skip there.
- Methodsize on Windows and the Windows `hs_err` check were not part of the ticket 12 W-full logs; Windows has no
  strict lane.
- The 9 conformance cases added by N1 pass on all three Linux lanes and (as `nt` stubs) on Windows.
