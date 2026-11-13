# Modern classfile backend

Generated classes and the support runtime target **Java 21 / classfile major
65 only**. The compiler writes classes and `StackMapTable` directly in Python;
it does not use a JVM postprocessor or javac to regenerate user code.

```bash
python -m pyjvm315.cli examples/hello.py -o build/jvm -c Hello
java -Xverify:all -cp build/jvm Hello
javap -v -cp build/jvm Hello
python -m pyjvm315.conformance tests/conformance --python python3.15
```

`compile_source`, `compile_file`, `Compiler` and `ClassFile` retain `target=21`
for explicit validation; all older targets are rejected. The target propagates
to every imported module. Runtime compilation uses `javac --release 21`.
These are non-preview classfiles (minor 0); Java 21 or newer is required.
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
CI runs the full focused corpus on target 21. Backend verification never
changes Python semantic lowering, nested/local-class support, or runtime object
representation. Tests also load classes with branches/joins, nulls, dead blocks,
exception handlers, wide locals and newly initialized objects under strict
verification, and check target propagation to imported modules.

The existing benchmark harness needs no special path: it compiles with the new
default and compares against its base compiler using the same workloads. Build
and verification cost remain outside kernel timing. Generated and runtime
classfiles both use major 65, regardless of the host JDK version.

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
modules no longer clash), importable shells of `_posixsubprocess`, `fcntl`, `pwd`, `grp` (every operation raises), and
the wait-status helpers of `os`.

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
  implicit base of PEP 695 classes), so the pure-Python `typing` imports on the 3.13 and 3.15 lanes. `_socket`, `fcntl`,
  `pwd`, `grp`, `resource` and `_posixsubprocess` are import-only shells.
- No `faulthandler`, real sockets, real `fcntl`/`pwd`/`grp`, no subprocess spawning (`_posixsubprocess.fork_exec`,
  `os.waitpid` raise), so tests that spawn interpreters (`script_helper`) cannot run.
- Test modules that depend on CPython internals (`sys.getrefcount`, `gc` tracking, `_testcapi`, bytecode, `marshal` of
  code objects) report those tests as errors or skips; the differing ones are listed by `run_cpython_test.py`.
- `f_globals` of a live frame omits some entries (for example functions defined in `__main__`).
