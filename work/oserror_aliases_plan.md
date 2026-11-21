# Plan: OSError leftovers (HANDOFF open item 11; written by a planning agent, 2026-11-16)

Three leftovers of the wave-1 OSError work (agent A, merge `967778a`):
- A. No `IOError` / `EnvironmentError` / `WindowsError` aliases.
- B. A user subclass of OSError called with `winerror=None` prints the `[Errno]` form.
- C. `WindowsNative.winError` builds 4-tuple `args` (HANDOFF "Known Windows gaps": "`WinError` exceptions carry 4 args, not 5").

## 1. CPython reference behaviour (measured on C:\Python313 3.13.17, 3.14.10, 3.15.2 on this machine; the three agree on every line below)

### A. Aliases
- `builtins.IOError is OSError`, `builtins.EnvironmentError is OSError`, and on Windows only `builtins.WindowsError is OSError`. All are in `dir(builtins)`. `IOError.__name__ == 'OSError'`, `repr(IOError) == "<class 'OSError'>"`.
- `raise IOError(2, "x")` raises `FileNotFoundError` (it is the OSError constructor); `except EnvironmentError` catches every OSError; `class A(IOError)` has `__bases__ == (OSError,)`.
- On POSIX `WindowsError` is not defined: using the name raises `NameError: name 'WindowsError' is not defined` at the point of evaluation. An `except WindowsError:` clause is only evaluated when an exception reaches it. (POSIX part from the language definition; not run here.)
- Related module aliases, already true or to check: `os.error`, `socket.error`, `select.error`, `mmap.error` are `OSError`; `io.BlockingIOError is BlockingIOError`.

### B. `winerror` (Windows; the 4th constructor argument)
The rule in `OSError.__init__`/`oserror_parse_args`: with 4 or 5 arguments the 4th is stored as `winerror` whatever it is; if it is an `int` (bool included) it is converted with `winerror_to_errno` and replaces `errno` in the object **and in `args[0]`**. `str()` uses the `[WinError ...]` form whenever the winerror slot is set, and it is set (to the object passed, even `None`) whenever 4+ arguments were given. Measured:

| Call | type | args | errno | winerror | str |
| --- | --- | --- | --- | --- | --- |
| `OSError(1, 'x', None, None)` | PermissionError | `(1, 'x', None, None)` | 1 | None | `[WinError None] x` |
| `OSError(1, 'x', None, 5)` | PermissionError | `(13, 'x', None, 5)` | 13 | 5 | `[WinError 5] x` |
| `OSError(1, 'x', 'f', None)` | PermissionError | `(1, 'x')` | 1 | None | `[WinError None] x: 'f'` |
| `OSError(1, 'x', 'f', 5)` | PermissionError | `(13, 'x')` | 13 | 5 | `[WinError 5] x: 'f'` |
| `OSError(1, 'x', None, 'w')` | PermissionError | `(1, 'x', None, 'w')` | 1 | 'w' | `[WinError w] x` |
| `OSError(1, 'x', None, 0)` | OSError | `(22, 'x', None, 0)` | 22 | 0 | `[WinError 0] x` |
| `OSError(1, 'x', None, True)` | OSError | `(22, 'x', None, True)` | 22 | True | `[WinError True] x` |
| `OSError(1, 'x', None, 5.0)` | PermissionError | `(1, 'x', None, 5.0)` | 1 | 5.0 | `[WinError 5.0] x` |
| `OSError(1, 'x', None, -1)` | OSError | `(22, 'x', None, -1)` | 22 | -1 | `[WinError -1] x` |
| `OSError(1, 'x', None, 2**40)` | raises `OverflowError: Python int too large to convert to C long` | | | | |
| `OSError(1, 'x', None, None, 'g')` | PermissionError | 5-tuple unchanged | 1 | None | `[WinError None] x` (no filename, so no ` -> 'g'`) |
| `OSError(1, 'x', 'f', None, 'g')` | PermissionError | `(1, 'x')` | 1 | None | `[WinError None] x: 'f' -> 'g'` |
| `OSError(2, 'x')` / `(2, 'x', 'f')` | FileNotFoundError | `(2, 'x')` | 2 | None | `[Errno 2] x` / `[Errno 2] x: 'f'` |

- A user subclass `class My(OSError): pass` gives **exactly the same rows** with type `My` (no remapping of the class). So `My(1, 'x', None, None)` prints `[WinError None] x`; that is the leftover. The same holds for a builtin subclass called directly (`PermissionError(1, 'x', None, None)`).
- A subclass that defines only `__new__` (and passes the arguments on) behaves like `My`. A subclass that defines `__init__` without calling the base gets empty fields: `args == ()`, `errno/strerror/filename/winerror` None, `str(e) == ''`.
- The attribute itself: `OSError.winerror` is a member descriptor; `OSError().winerror` and `OSError(2, 'x').winerror` are `None`. Assigning changes `str()`: `e = OSError(1, 'x', None, 5); e.winerror = None` gives `[WinError None] x`; `e = My(1, 'x'); e.winerror = 9` gives `[WinError 9] x`; `del e.winerror` returns to `[Errno 1] x` and reads `None` again. Assigning does not touch `errno` or `args`.
- Pickle/`__reduce__`: `(OSError, e.args)`; re-calling `OSError(*e.args)` with a 5-tuple reproduces the object.

### C. Exceptions raised by the runtime for a Win32 error
`PyErr_SetExcFromWindowsErrWithFilenameObjects` calls the class with **five** arguments `(errno, message, filename, winerror, filename2)`; the constructor then trims `args` to 2 when a filename is present. Measured:

| Source | args | filename / filename2 |
| --- | --- | --- |
| `winreg.OpenKey` missing key | `(2, 'The system cannot find the file specified', None, 2, None)` | None / None |
| `_winapi.CloseHandle(bad)` | `(9, 'The handle is invalid', None, 6, None)` | None / None |
| `_winapi.OpenProcess(bad pid)` | `(22, 'The parameter is incorrect', None, 87, None)` | None / None |
| `os.stat('missing')` | `(2, 'The system cannot find the file specified')` | `'missing'` / None |
| `os.rename('missing', 'other')` | `(2, 'The system cannot find the file specified')` | `'missing'` / `'other'` |
| `ctypes.WinError(5)` (Python-level `OSError(None, descr, None, code)`) | `(13, 'Access is denied.', None, 5)` | 4 args, message keeps its dot |

`repr` follows args: `OSError(9, 'The handle is invalid', None, 6, None)`, `FileNotFoundError(2, 'The system cannot find the file specified')`.

## 2. Current state (file:line)
### A
- The compiler knows builtin exceptions by name: `pyjvm315/compiler.py:41-55` (`EXCEPTION_TYPES`). The aliases are not in it. Uses: name load falls through to `builtinType(name)` at `compiler.py:535-536`, and an unknown name ends in `CompileError("Name 'IOError' referenced before assignment")` at `:540` (or `nsLoad` in exec-style code, `:537-539`); the dynamic-call test `:3050`; the `raise X(...)`/call fast path `:3353` (`exceptionN(name, args)`); `except` matching by name `:3542-3549` (`_except_name_is_variable`) and `:3551-3566` (`_emit_except_match`).
- `compiler.py:3547` already lists `EnvironmentError`, `IOError`, `WindowsError`, `PythonFinalizationError` as names that a star-importing module must *not* evaluate, so `except IOError:` is compiled to `exceptionMatches(exc, "IOError")`. In a module without a star import `_except_name_is_variable` also returns False for them (`:3549`), with the same result. `PyRuntime.exceptionMatches` (`PyRuntime.java:3002-3008`) then asks `exceptionIsSubclass(actual, "IOError")`, and `IOError` is not in `EXCEPTION_PARENTS` (`PyRuntime.java:3012-3038`, extended by `BuiltinsExtra.registerExceptionTypes`, `BuiltinsExtra.java:254-262`). **Reading of the code, not run: `except IOError:` compiles and silently never matches, `except (IOError, OSError):` matches through its second element, and `raise IOError(...)` / `IOError` as a value is a compile error.** Confirm with a two-line case before starting (step 0).
- Runtime: builtin types are `PyBuiltinType` objects interned by name (`PyRuntime.java:400`, `PyBuiltinType.java:24-30`: equality and identity are the name). The `builtins` namespace is filled from `EXCEPTION_PARENTS` in `Namespaces.java:93-111` (`builtinsDict`) and copied into the module at `:114-121`.
- CPython's stdlib (pinned corpus) uses the aliases 35 times in 16 files, almost all tests (`_compat_pickle.py` maps the names as strings; `profiling/sampling/heatmap_collector.py` has 6 real uses). The probe baseline has one affected body, `Lib/test/test_exceptions.py::ExceptionTests.test_WindowsError`.

### B
- Builtin OSError values: `BuiltinsExtra.newOSError` (`BuiltinsExtra.java:308-330`). It puts `winerror` into the attrs only when 4+ arguments were given (`:316`), and `PyExcArgs.str()` tests `attrs.containsKey("winerror")` (`PyExcArgs.java:25-29`). So `OSError(1, 'x', None, None)` already prints `[WinError None] x` (the existing case `oserror_errno_subclass.py` covers `(1, "x", None, None)` on win32 and passes).
- User subclasses: `BuiltinsExtra.initOSErrorFields` (`:336-357`) **always** stores `winerror` on Windows (`:345`, `null` when fewer than 4 arguments), and `osErrorStr` (`:360-368`) decides with `exc.fields.get("winerror") != null` (`:363`). So `My(1, 'x', None, None)` and `My(1, 'x', 'f', None)` print `[Errno 1] ...`. This is the leftover.
- Further differences in the same two functions (found by reading against the table above):
  - `initOSErrorFields:338` returns early when the class defines `__new__` *or* `__init__`; CPython still fills the fields for a class that defines only `__new__` (row "MyNew" of the probe).
  - Neither path raises `OverflowError` for an int winerror outside C long (`:315`, `:344` skip the mapping instead).
  - `initOSErrorFields:351-356` rebuilds `args` only when mapped or a filename is given; correct, but `args[0]` must be the mapped errno also in the 5-argument-without-filename form (it is: the loop at `:354` copies the tail). No change needed; keep a case row for it.
- Attribute reads of builtin values: `ImportSupport.exceptionAttr` (`ImportSupport.java:197-206`) falls back to `args[3]` for `winerror` when the payload has no attrs. Whether `e.winerror = v` / `del e.winerror` on a builtin `PyExceptionValue` is supported at all was not traced (unverified); user-subclass instances store fields in `exc.fields`, where assignment is plain attribute storage.
- Keyword arguments: `BuiltinsExtra.newExceptionCall` (`:274-282`) rejects `OSError(..., key=...)` with "keyword arguments are not supported by this runtime"; CPython's message is `OSError() takes no keyword arguments`. Trivial to fix while here.

### C
- `WindowsNative.winError(forcedType, code, filename)` (`WindowsNative.java:113-123`): builds `args = (errno, msg, filename, code)` in every case, so a call with a filename has 4-tuple args where CPython has `(errno, msg)`, and one without has 4 where CPython has 5. `repr()` and `e.args` differ; `str()`, `errno`, `winerror`, `filename` are right (attrs).
- Callers to re-check after the change: every `WindowsNative.winError(` use (grep; in `WindowsNtModule`, `WindowsRegModule`, `WindowsWinApiModule`, `WindowsHandles`, `WindowsIocp`, `WindowsOverlappedModule`) and the socket error builder at `WindowsSocketModule.java:177` (`set_error`, which in CPython is also `PyErr_SetExcFromWindowsErr`, so 5 args). No two-filename variant exists; `os.rename`/`replace`/`link` on Windows currently report errno-style through java.nio (HANDOFF "Known Windows gaps", first bullet), which is a separate item.

## 3. Design
### A. Aliases: resolve to `OSError` at the two edges, never create a second type
- Runtime (authoritative): one helper `PyRuntime.canonicalBuiltinType(String)` mapping `IOError`/`EnvironmentError` to `OSError`, and `WindowsError` to `OSError` when `ImportSupport.WINDOWS`, else raising `NameError("name 'WindowsError' is not defined")`. Call it at the top of `builtinType(Object)` (`PyRuntime.java:400`) so no `PyBuiltinType("IOError")` can ever be interned, and in `exceptionMatches` (`:3004`) and `BuiltinsExtra.newException`/`newExceptionCall` (`:274`, `:285`) for names that arrive as strings from already-compiled classes.
- `Namespaces.builtinsDict` (`Namespaces.java:97-100`): after the exception loop, `BUILTINS.put("IOError", builtinType("OSError"))`, same for `EnvironmentError`, and `WindowsError` only on Windows. This covers `builtins.IOError`, `getattr(builtins, ...)`, `dir(builtins)`, exec'd code (`nsLoad`) and `subprocess.py`'s `getattr(builtins, exception_name)`.
- Compiler: add `EXCEPTION_ALIASES = {"IOError": "OSError", "EnvironmentError": "OSError"}` and treat `WindowsError` separately.
  - `_load_name` (`compiler.py:535`): `name in EXCEPTION_ALIASES` loads `builtinType("OSError")`; `WindowsError` loads `builtinType("WindowsError")` (the runtime decides per OS; a class compiled on Linux and cached must still raise NameError there and work on Windows, so the decision cannot be made at compile time).
  - Call fast path (`:3353`) and dynamic test (`:3050`): accept the two fixed aliases with the canonical name; leave `WindowsError(...)` to the generic call path (load, then call).
  - `except` matching: `_except_name_is_variable` (`:3542-3549`) returns False for the two fixed aliases and the emitted name is the canonical one (the two `ldc_string` sites in `_emit_except_match`); for `WindowsError` it returns True so the handler evaluates the name at run time (`exceptionMatchesValue`), which yields NameError on POSIX exactly when CPython would. Remove the three names from the special list at `:3547` accordingly (keep `PythonFinalizationError`, which is a real 3.13+ exception: check that it is otherwise handled; it is not in `EXCEPTION_TYPES` either, so it has the same silent-never-matches reading. Out of scope, note it in HANDOFF).
  - All of these sit behind the existing "is the name rebound here" checks, so `IOError = 3` in user code keeps working.
- The compiler is self-hosted: the change must compile under the host CPython and under pyJVM itself (no new syntax; plain dict/set lookups).

### B. `winerror` slot semantics for user subclasses
- `initOSErrorFields`: store `winerror` only when `args.length >= 4` (the value passed, `null` for None); otherwise leave the key absent. `osErrorStr:363`: `boolean win = exc.fields.containsKey("winerror")`.
- Attribute read of an absent key must still give `None` on Windows (`My(2, 'x').winerror`): check how instance attribute lookup falls back for exception fields; if it raises AttributeError today, add `winerror` to the place that defaults `errno`/`strerror`/`filename` (search for where `filename2` defaults; unverified). `del e.winerror` then removes the key and returns to the `[Errno]` form for free; assignment of any value (None included) stores the key and gives the `[WinError]` form, as CPython.
- Raise `OverflowError("Python int too large to convert to C long")` in both `newOSError:315` and `initOSErrorFields:344` when an int winerror is outside the 32-bit C long range (Windows `long`), instead of skipping the mapping.
- `__new__`-only subclasses: change the guard at `:338` to skip only when the class defines `__init__` (a class that defines `__new__` and not `__init__` gets the fields from the arguments of the call). Confirm with the `MyNew`/`MyBoth`/`MyInit` rows before and after; if the object-construction path makes this awkward, leave it and record the difference.
- `OSError() takes no keyword arguments` in `newExceptionCall`.

### C. 5-tuple args
- `WindowsNative.winError`: build what the CPython constructor leaves: `filename != null` gives `args = (errno, msg)`; otherwise `(errno, msg, None, code, None)`. The attrs map (errno, strerror, filename, winerror) is unchanged, add `filename2 = null`. Same in the Winsock `set_error` builder (`WindowsSocketModule.java:177`) if it constructs its own tuple.
- No Python-visible API change besides `e.args`/`repr(e)`/pickle. Grep the conformance cases for printed `.args` or `repr` of Windows errors (`os_platform.py`, `os_pipe.py`, `subprocess_windows.py`, `overlapped_*`, `winapi_named_pipes.py`, `socket_loopback_basic.py`): their expected output comes from CPython at run time, so cases that were written around the 4-tuple (printing `len(e.args)` or avoiding args) can now print args directly.

## 4. Ordered steps
0. Scratch case (not committed) with `try: raise OSError(2,"x")` / `except IOError: print("caught")` and `print(IOError is OSError)` to confirm the "current state" reading of A.
1. Runtime alias helper + `builtinsDict` entries. Compiles alone; `builtins.IOError` works from exec'd code.
2. Compiler alias handling (load, call, except, `WindowsError` at run time). Case `oserror_aliases`.
3. B: `containsKey` semantics, OverflowError, `__new__`-only guard, keyword message. Extend `oserror_errno_subclass.py` (win32 block) or add `oserror_winerror_slot.py`.
4. C: `winError` args. Case `oserror_windows_args` (win32-gated).
5. Rerun on the three lanes (Windows): `oserror_errno_subclass`, the new cases, `os_platform`, `os_pipe`, `os_fchmod`, `os_putenv`, `subprocess_windows`, `winapi_named_pipes`, `overlapped_errors`, `socket_loopback_basic`, `excgroup_star_state`; pytest `tests/test_parser.py`/selfhost tests because the compiler changed; the probe (compiler change: `--min-compiles 2918` must not drop; `test_WindowsError` may move to COMPILES).
6. HANDOFF: close item 11's leftovers and the "4 args, not 5" bullet under Known Windows gaps.

## 5. Conformance cases
- `oserror_aliases.py` (all platforms): `IOError is OSError`, `EnvironmentError is OSError`, `IOError.__name__`, `repr(EnvironmentError)`; `import builtins`: `builtins.IOError is OSError`, `'IOError' in dir(builtins)`, `hasattr(builtins, 'WindowsError') == (sys.platform == 'win32')`; `raise IOError(2, 'x')` caught by `except FileNotFoundError`, type name printed; `raise OSError(13, 'x')` caught by `except EnvironmentError`; tuple form `except (IOError, ValueError)`; `class A(IOError)`: `A.__bases__`, `A.__mro__` names, `isinstance(A(), OSError)`; `except*` with `IOError`; alias used as a value (`errs = (IOError, KeyError)`; `issubclass(FileNotFoundError, IOError)`; `isinstance(e, EnvironmentError)`); a function that rebinds `IOError` locally; `os.error is OSError`, `io.BlockingIOError is BlockingIOError`; one star-import module shape (the `:3547` path) if a cheap one exists (e.g. `from errno import *` then `except IOError`). On win32: `WindowsError is OSError`, `except WindowsError`. Else: `try: WindowsError` / `except NameError as e: print(e)`, and a `try: raise OSError(1,'x')` / `except WindowsError:` that must raise NameError.
- `oserror_winerror_slot.py` (first line unconditional, body under `if sys.platform == "win32":`): the table of 1.B for `OSError`, `My`, `PermissionError` and the three `__new__`/`__init__` variants (print type name, args, errno, strerror, filename, filename2, winerror, str, repr); the OverflowError row; `e.winerror = None`, `= 7`, `del e.winerror` with `str(e)` after each; `OSError().winerror`, `My(2, 'x').winerror`; `OSError(1, 'x', key=1)` message.
- `oserror_windows_args.py` (win32): args, repr and `pickle`-free round trip `type(e)(*e.args)` for errors raised by `winreg.OpenKey` (missing key under HKCU), `_winapi.CloseHandle(987654)`, `os.stat` of a missing name, a closed socket operation; never print handles or addresses.

## 6. Risks and open questions
1. A is a compiler change in name resolution: small, but it touches every module's `except` compilation path; run the probe and the selfhost tests. Rough size: 15 lines Python, 40 lines Java.
2. Name-based exception identity: anything that compares type *names* (pickling by name, `__reduce__`, `_compat_pickle`) must only ever see `OSError`. Interning through `canonicalBuiltinType` guarantees that for objects; grep for other tables keyed by exception name (`ImportSupport.OS_ERRORS` at `ImportSupport.java:182`, `BUILTIN_EXCEPTION_NAMES` at `PyRuntime.java:3041`) and do **not** add the aliases there.
3. `WindowsError` on POSIX is verified only by reasoning (no POSIX CPython run here); the Linux lane will show it.
4. B's attribute default/delete path on builtin (non-subclass) exception values is untraced; if attribute assignment on `PyExceptionValue` is not supported at all, limit the case to the subclass rows and record the gap.
5. C changes `e.args` length for every Windows error: any runtime Java code that reads `args[3]` for winerror (the fallback at `ImportSupport.java:204` does) keeps working because the index is unchanged; code that tests `args.size() == 4` would not. Grep `items.size() >= 4` / `size() == 4` near winerror before the change.
6. Rough total: one session; B and C are Windows-only in effect, A needs both OSes.
