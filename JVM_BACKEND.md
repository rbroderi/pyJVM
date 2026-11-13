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
types numbered as in the emulated version's `token.py`, positions, and the physical `line` of every token. Known gaps:

- `readline` is read to the end on the first `__next__`; CPython pulls one line at a time (visible only with an
  interactive or infinite source).
- Tokens before a lexical error are yielded and then `SyntaxError` / `IndentationError` is raised, as in CPython, but
  the message and offset are the pyJVM parser's and do not always match the C tokenizer's. Covered: unterminated
  strings, unclosed brackets (`unexpected EOF in multi-line statement`), inconsistent dedent. Not diagnosed at this
  level: mixed tabs and spaces (`TabError`) and malformed numbers (`0x`, `1_` lex as NUMBER followed by NAME/OP).
- `extra_tokens=False` (the parser's own token view, reached only through `python -m tokenize -e` and CPython's tests) is
  derived from the same stream: exact operator types, no COMMENT/NL, empty NEWLINE/INDENT/DEDENT strings with CPython's
  `-1` columns. Type comments and the C tokenizer's `\r` handling are not reproduced.
- Under the 3.13 lane `t"..."` lexes as NAME + STRING, from 3.14 on as TSTRING tokens.

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

Current state (3.15 lane): `test_bool` 23 of 31 tests match CPython. Starting a test module costs minutes the first
time (stdlib modules are compiled at run time by the self-hosted compiler); later runs reuse `PYJVM_CACHE`.

### Known gaps

- `unittest.mock` imports `asyncio`, which needs `_socket` (and `_typing` for the pure-Python `typing`); both are
  missing, so `unittest.mock` does not import yet. `tests/conformance/unittest_mock_basic.py` is written but not in the
  passing set.
- No `faulthandler`, `_socket`, real `fcntl`/`pwd`/`grp`, no subprocess spawning (`_posixsubprocess.fork_exec`,
  `os.waitpid` raise), so tests that spawn interpreters (`script_helper`) cannot run.
- Test modules that depend on CPython internals (`sys.getrefcount`, `gc` tracking, `_testcapi`, bytecode, `marshal` of
  code objects) report those tests as errors or skips; the differing ones are listed by `run_cpython_test.py`.
- `f_globals` of a live frame omits some entries (for example functions defined in `__main__`).
