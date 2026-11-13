# Standard library status

Generated from `tools/stdlib_manifest.json` by `python3 tools/stdlib_status.py report`; do not edit by hand.
The manifest records, per module and per language lane, how far the CPython `Lib` source gets on pyJVM

- **smoke**: compiles, imports from the stdlib jar on the JVM, and its public names and a short behavioural check match the reference interpreter
- **imports**: imports from the jar, but the names or the behavioural check differ from the reference (reason given)
- **compiles**: compiles to a class file, but the import fails on the JVM (reason given)
- **unsupported**: the compiler rejects it (reason given)
- **excluded**: not part of the build (test suites, IDLE and the like)

## Lanes

| Lane | Reference interpreter | Library | Modules |
|---|---|---|---|
| 3.13 | Python 3.13.18 (main, Oct 26 2026, 14:08:19) [Clang 23.1.3 ] | the interpreter's own Lib | 536 |
| 3.15 | Python 3.15.0b4 (main, Jul 18 2026, 17:04:20) [Clang 22.1.3 ] | the interpreter's own Lib | 608 |

## Module counts by status

| Status | 3.13 | 3.15 |
|---|---|---|
| smoke | 283 | 286 |
| imports | 16 | 23 |
| compiles | 235 | 297 |
| unsupported | 2 | 2 |
| **total** | **536** | **608** |

Excluded directories: `__phello__` (frozen-module test package), `idlelib` (IDLE (needs tkinter)), `test` (CPython's own regression suite), `tests` (test suite of a package), `turtledemo` (turtle demo programs (need tkinter)).

## Top blockers, lane 3.13

| Modules | Blocker | Examples |
|---|---|---|
| 73 | missing-native: _socket | `asyncio`, `asyncio.__main__`, `asyncio.base_events`, `asyncio.base_futures`, `asyncio.base_subprocess`, ... |
| 51 | missing-native: _typing | `_pyrepl._threading_handler`, `_pyrepl.base_eventqueue`, `_pyrepl.completing_reader`, `_pyrepl.console`, `_pyrepl.historical_reader`, ... |
| 16 | smoke-mismatch | `_colorize`, `_ios_support`, `_pyrepl.commands`, `_pyrepl.pager`, `_pyrepl.trace`, ... |
| 12 | missing-native: _ctypes | `_pyrepl._minimal_curses`, `_pyrepl.curses`, `_pyrepl.unix_eventqueue`, `ctypes`, `ctypes._aix`, ... |
| 9 | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' | `email.mime.application`, `email.mime.audio`, `email.mime.base`, `email.mime.image`, `email.mime.message`, ... |
| 8 | missing-native: _posixsubprocess | `antigravity`, `ensurepip`, `ensurepip.__main__`, `ensurepip._uninstall`, `subprocess`, ... |
| 7 | missing-native: _codecs_iso2022 | `encodings.iso2022_jp`, `encodings.iso2022_jp_1`, `encodings.iso2022_jp_2`, `encodings.iso2022_jp_2004`, `encodings.iso2022_jp_3`, ... |
| 7 | missing-native: _codecs_jp | `encodings.cp932`, `encodings.euc_jis_2004`, `encodings.euc_jisx0213`, `encodings.euc_jp`, `encodings.shift_jis`, ... |
| 7 | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' | `importlib.metadata._adapters`, `importlib.metadata._collections`, `importlib.metadata._functools`, `importlib.metadata._itertools`, `importlib.metadata._meta`, ... |
| 5 | missing-native: _curses | `curses`, `curses.ascii`, `curses.has_key`, `curses.panel`, `curses.textpad` |
| 5 | missing-native: _sqlite3 | `dbm.sqlite3`, `sqlite3`, `sqlite3.__main__`, `sqlite3.dbapi2`, `sqlite3.dump` |
| 4 | missing-native: _codecs_cn | `encodings.gb18030`, `encodings.gb2312`, `encodings.gbk`, `encodings.hz` |
| 4 | missing-native: termios | `_pyrepl.fancy_termios`, `_pyrepl.unix_console`, `pty`, `tty` |
| 4 | missing-native: unicodedata | `_pyrepl.input`, `_pyrepl.utils`, `encodings.idna`, `stringprep` |
| 4 | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' | `zoneinfo`, `zoneinfo._common`, `zoneinfo._tzpath`, `zoneinfo._zoneinfo` |
| 3 | missing-native: _codecs_kr | `encodings.cp949`, `encodings.euc_kr`, `encodings.johab` |
| 3 | missing-native: pyexpat | `plistlib`, `xml.dom.expatbuilder`, `xml.parsers.expat` |
| 3 | runtime-error: KeyError: '__main__' | `_strptime`, `calendar`, `mailbox` |
| 2 | missing-native: _bz2 | `bz2`, `encodings.bz2_codec` |
| 2 | missing-native: _codecs_tw | `encodings.big5`, `encodings.cp950` |
| 2 | missing-native: _operator | `hmac`, `secrets` |
| 1 | compiler-crash: ValueError: JVM UTF8 constant exceeds 65535 bytes | `pydoc_data.topics` |
| 1 | compiler-gap: import from module '_collections' is not available | `importlib.metadata` |
| 1 | missing-native: _codecs_hk | `encodings.big5hkscs` |
| 1 | missing-native: _csv | `csv` |
| 18 | 18 further reasons | |

## Top blockers, lane 3.15

| Modules | Blocker | Examples |
|---|---|---|
| 75 | missing-native: _socket | `asyncio`, `asyncio.__main__`, `asyncio.base_events`, `asyncio.base_futures`, `asyncio.base_subprocess`, ... |
| 29 | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' | `profiling.sampling`, `profiling.sampling.__main__`, `profiling.sampling._child_monitor`, `profiling.sampling._css_utils`, `profiling.sampling._format_utils`, ... |
| 23 | smoke-mismatch | `_ast_unparse`, `_colorize`, `_ios_support`, `_pyrepl._threading_handler`, `_pyrepl.pager`, ... |
| 19 | runtime-error: KeyError: '__mro__' | `_pyrepl._module_completer`, `_pyrepl.fancycompleter`, `bdb`, `doctest`, `importlib.readers`, ... |
| 14 | missing-native: _tkinter | `tkinter`, `tkinter.__main__`, `tkinter.colorchooser`, `tkinter.commondialog`, `tkinter.constants`, ... |
| 14 | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' | `pprint`, `unittest`, `unittest.__main__`, `unittest._log`, `unittest.async_case`, ... |
| 12 | missing-native: _typing | `_pyrepl.base_eventqueue`, `_pyrepl.commands`, `_pyrepl.completing_reader`, `_pyrepl.console`, `_pyrepl.historical_reader`, ... |
| 11 | missing-native: _ctypes | `_pyrepl.windows_console`, `ctypes`, `ctypes._aix`, `ctypes._endian`, `ctypes._layout`, ... |
| 9 | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' | `importlib.metadata._adapters`, `importlib.metadata._collections`, `importlib.metadata._context`, `importlib.metadata._functools`, `importlib.metadata._itertools`, ... |
| 9 | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' | `email.mime.application`, `email.mime.audio`, `email.mime.base`, `email.mime.image`, `email.mime.message`, ... |
| 8 | missing-native: _posixsubprocess | `antigravity`, `ensurepip`, `ensurepip.__main__`, `ensurepip._uninstall`, `subprocess`, ... |
| 7 | missing-native: _codecs_iso2022 | `encodings.iso2022_jp`, `encodings.iso2022_jp_1`, `encodings.iso2022_jp_2`, `encodings.iso2022_jp_2004`, `encodings.iso2022_jp_3`, ... |
| 7 | missing-native: _codecs_jp | `encodings.cp932`, `encodings.euc_jis_2004`, `encodings.euc_jisx0213`, `encodings.euc_jp`, `encodings.shift_jis`, ... |
| 6 | missing-native: _sqlite3 | `dbm.sqlite3`, `sqlite3`, `sqlite3.__main__`, `sqlite3._completer`, `sqlite3.dbapi2`, ... |
| 5 | missing-native: _curses | `curses`, `curses.ascii`, `curses.has_key`, `curses.panel`, `curses.textpad` |
| 5 | missing-native: unicodedata | `_pyrepl.content`, `_pyrepl.input`, `_pyrepl.utils`, `encodings.idna`, `stringprep` |
| 4 | missing-native: _codecs_cn | `encodings.gb18030`, `encodings.gb2312`, `encodings.gbk`, `encodings.hz` |
| 4 | missing-native: _interpreters | `concurrent.futures.interpreter`, `concurrent.interpreters`, `concurrent.interpreters._crossinterp`, `concurrent.interpreters._queues` |
| 4 | missing-native: _lsprof | `cProfile`, `profiling.tracing`, `profiling.tracing.__main__`, `profiling.tracing._utils` |
| 4 | missing-native: termios | `_pyrepl.fancy_termios`, `_pyrepl.unix_console`, `pty`, `tty` |
| 4 | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' | `zoneinfo`, `zoneinfo._common`, `zoneinfo._tzpath`, `zoneinfo._zoneinfo` |
| 4 | runtime-error: AttributeError: module 'sys' has no attribute 'getrecursionlimit' | `tomllib`, `tomllib._parser`, `tomllib._re`, `tomllib._types` |
| 3 | missing-native: _bz2 | `bz2`, `compression.bz2`, `encodings.bz2_codec` |
| 3 | missing-native: _codecs_kr | `encodings.cp949`, `encodings.euc_kr`, `encodings.johab` |
| 3 | missing-native: pyexpat | `plistlib`, `xml.dom.expatbuilder`, `xml.parsers.expat` |
| 36 | 26 further reasons | |

## Modules that are not at `smoke`

<details><summary>Lane 3.13: 253 modules</summary>

| Module | Status | Reason |
|---|---|---|
| `_colorize` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_ios_support` | imports | smoke-mismatch: names differ: missing c_char_p,c_void_p,cdll,lib; extra none |
| `_pyrepl._minimal_curses` | compiles | missing-native: _ctypes |
| `_pyrepl._threading_handler` | compiles | missing-native: _typing |
| `_pyrepl.base_eventqueue` | compiles | missing-native: _typing |
| `_pyrepl.commands` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.completing_reader` | compiles | missing-native: _typing |
| `_pyrepl.console` | compiles | missing-native: _typing |
| `_pyrepl.curses` | compiles | missing-native: _ctypes |
| `_pyrepl.fancy_termios` | compiles | missing-native: termios |
| `_pyrepl.historical_reader` | compiles | missing-native: _typing |
| `_pyrepl.input` | compiles | missing-native: unicodedata |
| `_pyrepl.pager` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.reader` | compiles | missing-native: _typing |
| `_pyrepl.readline` | compiles | missing-native: _typing |
| `_pyrepl.simple_interact` | compiles | missing-native: _typing |
| `_pyrepl.trace` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.unix_console` | compiles | missing-native: termios |
| `_pyrepl.unix_eventqueue` | compiles | missing-native: _ctypes |
| `_pyrepl.utils` | compiles | missing-native: unicodedata |
| `_pyrepl.windows_console` | compiles | missing-native: msvcrt |
| `_pyrepl.windows_eventqueue` | compiles | missing-native: _typing |
| `_strptime` | compiles | runtime-error: KeyError: '__main__' |
| `antigravity` | compiles | missing-native: _posixsubprocess |
| `argparse` | imports | smoke-mismatch: check differs: jvm "!AttributeError: module 'os' has no attribute 'terminal_size'" vs reference "{'n': 3}" |
| `ast` | imports | smoke-mismatch: names differ: missing IntEnum,auto,contextmanager,get_source_segment,main,nullcontext...; extra Interpolation,TemplateStr,compare |
| `asyncio` | compiles | missing-native: _socket |
| `asyncio.__main__` | compiles | missing-native: _socket |
| `asyncio.base_events` | compiles | missing-native: _socket |
| `asyncio.base_futures` | compiles | missing-native: _socket |
| `asyncio.base_subprocess` | compiles | missing-native: _socket |
| `asyncio.base_tasks` | compiles | missing-native: _socket |
| `asyncio.constants` | compiles | missing-native: _socket |
| `asyncio.coroutines` | compiles | missing-native: _socket |
| `asyncio.events` | compiles | missing-native: _socket |
| `asyncio.exceptions` | compiles | missing-native: _socket |
| `asyncio.format_helpers` | compiles | missing-native: _socket |
| `asyncio.futures` | compiles | missing-native: _socket |
| `asyncio.locks` | compiles | missing-native: _socket |
| `asyncio.log` | compiles | missing-native: _socket |
| `asyncio.mixins` | compiles | missing-native: _socket |
| `asyncio.proactor_events` | compiles | missing-native: _socket |
| `asyncio.protocols` | compiles | missing-native: _socket |
| `asyncio.queues` | compiles | missing-native: _socket |
| `asyncio.runners` | compiles | missing-native: _socket |
| `asyncio.selector_events` | compiles | missing-native: _socket |
| `asyncio.sslproto` | compiles | missing-native: _socket |
| `asyncio.staggered` | compiles | missing-native: _socket |
| `asyncio.streams` | compiles | missing-native: _socket |
| `asyncio.subprocess` | compiles | missing-native: _socket |
| `asyncio.taskgroups` | compiles | missing-native: _socket |
| `asyncio.tasks` | compiles | missing-native: _socket |
| `asyncio.threads` | compiles | missing-native: _socket |
| `asyncio.timeouts` | compiles | missing-native: _socket |
| `asyncio.transports` | compiles | missing-native: _socket |
| `asyncio.trsock` | compiles | missing-native: _socket |
| `asyncio.unix_events` | compiles | missing-native: _socket |
| `asyncio.windows_events` | compiles | missing-native: _socket |
| `asyncio.windows_utils` | compiles | missing-native: _socket |
| `bdb` | compiles | missing-native: _typing |
| `bz2` | compiles | missing-native: _bz2 |
| `cProfile` | compiles | missing-native: _lsprof |
| `calendar` | compiles | runtime-error: KeyError: '__main__' |
| `cmd` | compiles | missing-native: _typing |
| `concurrent.futures.process` | compiles | missing-native: _socket |
| `configparser` | imports | smoke-mismatch: check differs: jvm '!TypeError: implementation() takes no arguments' vs reference "'1'" |
| `csv` | compiles | missing-native: _csv |
| `ctypes` | compiles | missing-native: _ctypes |
| `ctypes._aix` | compiles | missing-native: _ctypes |
| `ctypes._endian` | compiles | missing-native: _ctypes |
| `ctypes.macholib` | compiles | missing-native: _ctypes |
| `ctypes.macholib.dyld` | compiles | missing-native: _ctypes |
| `ctypes.macholib.dylib` | compiles | missing-native: _ctypes |
| `ctypes.macholib.framework` | compiles | missing-native: _ctypes |
| `ctypes.util` | compiles | missing-native: _ctypes |
| `ctypes.wintypes` | compiles | missing-native: _ctypes |
| `curses` | compiles | missing-native: _curses |
| `curses.ascii` | compiles | missing-native: _curses |
| `curses.has_key` | compiles | missing-native: _curses |
| `curses.panel` | compiles | missing-native: _curses |
| `curses.textpad` | compiles | missing-native: _curses |
| `dataclasses` | compiles | missing-native: _typing |
| `dbm.gnu` | compiles | missing-native: _gdbm |
| `dbm.ndbm` | compiles | missing-native: _dbm |
| `dbm.sqlite3` | compiles | missing-native: _sqlite3 |
| `doctest` | compiles | missing-native: _typing |
| `email.mime.application` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.audio` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.base` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.image` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.message` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.multipart` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.nonmultipart` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.text` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.policy` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `encodings.big5` | compiles | missing-native: _codecs_tw |
| `encodings.big5hkscs` | compiles | missing-native: _codecs_hk |
| `encodings.bz2_codec` | compiles | missing-native: _bz2 |
| `encodings.cp932` | compiles | missing-native: _codecs_jp |
| `encodings.cp949` | compiles | missing-native: _codecs_kr |
| `encodings.cp950` | compiles | missing-native: _codecs_tw |
| `encodings.euc_jis_2004` | compiles | missing-native: _codecs_jp |
| `encodings.euc_jisx0213` | compiles | missing-native: _codecs_jp |
| `encodings.euc_jp` | compiles | missing-native: _codecs_jp |
| `encodings.euc_kr` | compiles | missing-native: _codecs_kr |
| `encodings.gb18030` | compiles | missing-native: _codecs_cn |
| `encodings.gb2312` | compiles | missing-native: _codecs_cn |
| `encodings.gbk` | compiles | missing-native: _codecs_cn |
| `encodings.hz` | compiles | missing-native: _codecs_cn |
| `encodings.idna` | compiles | missing-native: unicodedata |
| `encodings.iso2022_jp` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_1` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_2` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_2004` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_3` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_ext` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_kr` | compiles | missing-native: _codecs_iso2022 |
| `encodings.johab` | compiles | missing-native: _codecs_kr |
| `encodings.mbcs` | compiles | missing-native: codecs.mbcs_encode |
| `encodings.oem` | compiles | missing-native: codecs.oem_encode |
| `encodings.shift_jis` | compiles | missing-native: _codecs_jp |
| `encodings.shift_jis_2004` | compiles | missing-native: _codecs_jp |
| `encodings.shift_jisx0213` | compiles | missing-native: _codecs_jp |
| `encodings.utf_7` | compiles | runtime-error: AttributeError: module 'codecs' has no attribute 'utf_7_encode' |
| `ensurepip` | compiles | missing-native: _posixsubprocess |
| `ensurepip.__main__` | compiles | missing-native: _posixsubprocess |
| `ensurepip._uninstall` | compiles | missing-native: _posixsubprocess |
| `ftplib` | compiles | missing-native: _socket |
| `hashlib` | imports | smoke-mismatch: names differ: missing pbkdf2_hmac; extra none |
| `hmac` | compiles | missing-native: _operator |
| `http.client` | compiles | missing-native: _socket |
| `http.cookiejar` | compiles | missing-native: _socket |
| `http.server` | compiles | missing-native: _socket |
| `imaplib` | compiles | runtime-error: RuntimeError: Duplicate method name "run" with signature "(Ljava.lang.Object;Ljava.lang.Object;)Ljava.lang.Object;" in class file pyjvm315/r |
| `importlib.abc` | compiles | missing-native: _typing |
| `importlib.metadata` | unsupported | compiler-gap: import from module '_collections' is not available |
| `importlib.metadata._adapters` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._collections` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._functools` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._itertools` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._meta` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._text` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata.diagnose` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.readers` | compiles | missing-native: _typing |
| `importlib.resources` | compiles | missing-native: _typing |
| `importlib.resources._adapters` | compiles | missing-native: _typing |
| `importlib.resources._common` | compiles | missing-native: _typing |
| `importlib.resources._functional` | compiles | missing-native: _typing |
| `importlib.resources._itertools` | compiles | missing-native: _typing |
| `importlib.resources.abc` | compiles | missing-native: _typing |
| `importlib.resources.readers` | compiles | missing-native: _typing |
| `importlib.resources.simple` | compiles | missing-native: _typing |
| `importlib.simple` | compiles | missing-native: _typing |
| `inspect` | compiles | missing-native: _typing |
| `logging.config` | compiles | missing-native: _socket |
| `logging.handlers` | compiles | missing-native: _socket |
| `lzma` | compiles | missing-native: _lzma |
| `mailbox` | compiles | runtime-error: KeyError: '__main__' |
| `multiprocessing` | compiles | missing-native: _socket |
| `multiprocessing.connection` | compiles | missing-native: _socket |
| `multiprocessing.context` | compiles | missing-native: _socket |
| `multiprocessing.dummy` | compiles | missing-native: _socket |
| `multiprocessing.dummy.connection` | compiles | missing-native: _socket |
| `multiprocessing.forkserver` | compiles | missing-native: _socket |
| `multiprocessing.heap` | compiles | missing-native: _socket |
| `multiprocessing.managers` | compiles | missing-native: _socket |
| `multiprocessing.pool` | compiles | missing-native: _socket |
| `multiprocessing.popen_fork` | compiles | missing-native: _socket |
| `multiprocessing.popen_forkserver` | compiles | missing-native: _socket |
| `multiprocessing.popen_spawn_posix` | compiles | missing-native: _socket |
| `multiprocessing.popen_spawn_win32` | compiles | missing-native: _socket |
| `multiprocessing.process` | compiles | missing-native: _socket |
| `multiprocessing.queues` | compiles | missing-native: _socket |
| `multiprocessing.reduction` | compiles | missing-native: _socket |
| `multiprocessing.resource_sharer` | compiles | missing-native: _socket |
| `multiprocessing.resource_tracker` | compiles | missing-native: _socket |
| `multiprocessing.shared_memory` | compiles | missing-native: _socket |
| `multiprocessing.sharedctypes` | compiles | missing-native: _socket |
| `multiprocessing.spawn` | compiles | missing-native: _socket |
| `multiprocessing.synchronize` | compiles | missing-native: _socket |
| `multiprocessing.util` | compiles | missing-native: _socket |
| `os` | imports | smoke-mismatch: names differ: missing CLD_CONTINUED,CLD_DUMPED,CLD_EXITED,CLD_KILLED,CLD_STOPPED,CLD_TRAPPED...; extra none |
| `pdb` | compiles | missing-native: _typing |
| `pickle` | imports | smoke-mismatch: names differ: missing PickleBuffer; extra none |
| `pkgutil` | compiles | missing-native: _typing |
| `plistlib` | compiles | missing-native: pyexpat |
| `poplib` | compiles | missing-native: _socket |
| `pprint` | compiles | missing-native: _typing |
| `pstats` | compiles | missing-native: _typing |
| `pty` | compiles | missing-native: termios |
| `pydoc` | compiles | missing-native: _typing |
| `pydoc_data.topics` | unsupported | compiler-crash: ValueError: JVM UTF8 constant exceeds 65535 bytes |
| `rlcompleter` | compiles | missing-native: _typing |
| `secrets` | compiles | missing-native: _operator |
| `selectors` | imports | smoke-mismatch: names differ: missing EpollSelector; extra none |
| `shutil` | imports | smoke-mismatch: names differ: missing disk_usage; extra none |
| `signal` | imports | smoke-mismatch: names differ: missing pidfd_send_signal,sigtimedwait,sigwaitinfo,struct_siginfo; extra none |
| `site` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'copyright' |
| `sitecustomize` | compiles | runtime-error: IndexError: index out of range |
| `smtplib` | compiles | missing-native: _socket |
| `socket` | compiles | missing-native: _socket |
| `socketserver` | compiles | missing-native: _socket |
| `sqlite3` | compiles | missing-native: _sqlite3 |
| `sqlite3.__main__` | compiles | missing-native: _sqlite3 |
| `sqlite3.dbapi2` | compiles | missing-native: _sqlite3 |
| `sqlite3.dump` | compiles | missing-native: _sqlite3 |
| `ssl` | compiles | missing-native: _ssl |
| `statistics` | compiles | missing-native: math.sumprod |
| `stringprep` | compiles | missing-native: unicodedata |
| `subprocess` | compiles | missing-native: _posixsubprocess |
| `symtable` | compiles | missing-native: _symtable |
| `tarfile` | compiles | runtime-error: TypeError: __slots__ must be a string or iterable of strings |
| `tomllib` | compiles | missing-native: _typing |
| `tomllib._parser` | compiles | missing-native: _typing |
| `tomllib._re` | compiles | missing-native: _typing |
| `tomllib._types` | compiles | missing-native: _typing |
| `trace` | compiles | missing-native: _typing |
| `tracemalloc` | compiles | missing-native: _tracemalloc |
| `tty` | compiles | missing-native: termios |
| `turtle` | compiles | missing-native: tkinter |
| `types` | imports | smoke-mismatch: check differs: jvm '!TypeError: implementation() takes no arguments' vs reference "(namespace(a=1), 'function', 'mappingproxy')" |
| `typing` | compiles | missing-native: _typing |
| `unittest` | compiles | missing-native: _typing |
| `unittest.__main__` | compiles | missing-native: _typing |
| `unittest._log` | compiles | missing-native: _typing |
| `unittest.async_case` | compiles | missing-native: _typing |
| `unittest.case` | compiles | missing-native: _typing |
| `unittest.loader` | compiles | missing-native: _typing |
| `unittest.main` | compiles | missing-native: _typing |
| `unittest.mock` | compiles | missing-native: _typing |
| `unittest.result` | compiles | missing-native: _typing |
| `unittest.runner` | compiles | missing-native: _typing |
| `unittest.signals` | compiles | missing-native: _typing |
| `unittest.suite` | compiles | missing-native: _typing |
| `unittest.util` | compiles | missing-native: _typing |
| `urllib.parse` | imports | smoke-mismatch: check differs: jvm '!KeyError: 97' vs reference "(ParseResult(scheme='http', netloc='u:p@h:80', path='/a/b', params=''," |
| `urllib.request` | compiles | missing-native: _socket |
| `urllib.robotparser` | compiles | missing-native: _socket |
| `venv` | compiles | missing-native: _posixsubprocess |
| `venv.__main__` | compiles | missing-native: _posixsubprocess |
| `webbrowser` | compiles | missing-native: _posixsubprocess |
| `wsgiref.simple_server` | compiles | missing-native: _socket |
| `wsgiref.types` | compiles | missing-native: _typing |
| `xml.dom.expatbuilder` | compiles | missing-native: pyexpat |
| `xml.parsers.expat` | compiles | missing-native: pyexpat |
| `xml.sax.expatreader` | compiles | runtime-error: SAXReaderNotAvailable: expat not supported |
| `xml.sax.saxutils` | compiles | missing-native: _socket |
| `xmlrpc.client` | compiles | missing-native: _socket |
| `xmlrpc.server` | compiles | missing-native: _socket |
| `zoneinfo` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._common` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._tzpath` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._zoneinfo` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |

</details>

<details><summary>Lane 3.15: 322 modules</summary>

| Module | Status | Reason |
|---|---|---|
| `_ast_unparse` | imports | smoke-mismatch: names differ: missing PyCF_ALLOW_TOP_LEVEL_AWAIT,PyCF_TYPE_COMMENTS; extra AugLoad,AugStore,ExtSlice,Index... |
| `_colorize` | imports | smoke-mismatch: names differ: missing ANSIColors,Argparse,Ast,BackgroundStyle,COLORIZE,Calendar...; extra !ModuleNotFoundError:,'_typing',No,module... |
| `_ios_support` | imports | smoke-mismatch: names differ: missing c_char_p,c_void_p,cdll,lib; extra none |
| `_pyrepl._module_completer` | compiles | runtime-error: KeyError: '__mro__' |
| `_pyrepl._threading_handler` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.base_eventqueue` | compiles | missing-native: _typing |
| `_pyrepl.commands` | compiles | missing-native: _typing |
| `_pyrepl.completing_reader` | compiles | missing-native: _typing |
| `_pyrepl.console` | compiles | missing-native: _typing |
| `_pyrepl.content` | compiles | missing-native: unicodedata |
| `_pyrepl.fancy_termios` | compiles | missing-native: termios |
| `_pyrepl.fancycompleter` | compiles | runtime-error: KeyError: '__mro__' |
| `_pyrepl.historical_reader` | compiles | missing-native: _typing |
| `_pyrepl.input` | compiles | missing-native: unicodedata |
| `_pyrepl.layout` | compiles | missing-native: _typing |
| `_pyrepl.pager` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.reader` | compiles | missing-native: _typing |
| `_pyrepl.readline` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'copyright' |
| `_pyrepl.render` | compiles | missing-native: _typing |
| `_pyrepl.simple_interact` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'copyright' |
| `_pyrepl.terminfo` | compiles | runtime-error: AttributeError: type object 'TermInfo' has no attribute '__doc__' |
| `_pyrepl.trace` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.unix_console` | compiles | missing-native: termios |
| `_pyrepl.unix_eventqueue` | compiles | runtime-error: AttributeError: type object 'TermInfo' has no attribute '__doc__' |
| `_pyrepl.utils` | compiles | missing-native: unicodedata |
| `_pyrepl.windows_console` | compiles | missing-native: _ctypes |
| `_pyrepl.windows_eventqueue` | compiles | missing-native: _typing |
| `_strptime` | compiles | runtime-error: KeyError: '__main__' |
| `antigravity` | compiles | missing-native: _posixsubprocess |
| `argparse` | imports | smoke-mismatch: check differs: jvm "!AttributeError: module 'os' has no attribute 'terminal_size'" vs reference "{'n': 3}" |
| `ast` | imports | smoke-mismatch: names differ: missing get_source_segment,main,unparse; extra none |
| `asyncio` | compiles | missing-native: _socket |
| `asyncio.__main__` | compiles | missing-native: _socket |
| `asyncio.base_events` | compiles | missing-native: _socket |
| `asyncio.base_futures` | compiles | missing-native: _socket |
| `asyncio.base_subprocess` | compiles | missing-native: _socket |
| `asyncio.base_tasks` | compiles | missing-native: _socket |
| `asyncio.constants` | compiles | missing-native: _socket |
| `asyncio.coroutines` | compiles | missing-native: _socket |
| `asyncio.events` | compiles | missing-native: _socket |
| `asyncio.exceptions` | compiles | missing-native: _socket |
| `asyncio.format_helpers` | compiles | missing-native: _socket |
| `asyncio.futures` | compiles | missing-native: _socket |
| `asyncio.graph` | compiles | missing-native: _socket |
| `asyncio.locks` | compiles | missing-native: _socket |
| `asyncio.log` | compiles | missing-native: _socket |
| `asyncio.mixins` | compiles | missing-native: _socket |
| `asyncio.proactor_events` | compiles | missing-native: _socket |
| `asyncio.protocols` | compiles | missing-native: _socket |
| `asyncio.queues` | compiles | missing-native: _socket |
| `asyncio.runners` | compiles | missing-native: _socket |
| `asyncio.selector_events` | compiles | missing-native: _socket |
| `asyncio.sslproto` | compiles | missing-native: _socket |
| `asyncio.staggered` | compiles | missing-native: _socket |
| `asyncio.streams` | compiles | missing-native: _socket |
| `asyncio.subprocess` | compiles | missing-native: _socket |
| `asyncio.taskgroups` | compiles | missing-native: _socket |
| `asyncio.tasks` | compiles | missing-native: _socket |
| `asyncio.threads` | compiles | missing-native: _socket |
| `asyncio.timeouts` | compiles | missing-native: _socket |
| `asyncio.tools` | compiles | missing-native: _socket |
| `asyncio.transports` | compiles | missing-native: _socket |
| `asyncio.trsock` | compiles | missing-native: _socket |
| `asyncio.unix_events` | compiles | missing-native: _socket |
| `asyncio.windows_events` | compiles | missing-native: _socket |
| `asyncio.windows_utils` | compiles | missing-native: _socket |
| `base64` | imports | smoke-mismatch: check differs: jvm "!AttributeError: module 'binascii' has no attribute 'b2a_base32'" vs reference "(b'aGVsbG8gd29ybGQ=', b'hi', b'MFRA====', b'FF')" |
| `bdb` | compiles | runtime-error: KeyError: '__mro__' |
| `bz2` | compiles | missing-native: _bz2 |
| `cProfile` | compiles | missing-native: _lsprof |
| `calendar` | compiles | runtime-error: KeyError: '__main__' |
| `compileall` | compiles | runtime-error: AttributeError: 'PycInvalidationMode' object has no attribute '__init__' |
| `compression.bz2` | compiles | missing-native: _bz2 |
| `compression.lzma` | compiles | missing-native: _lzma |
| `compression.zlib` | imports | smoke-mismatch: names differ: missing none; extra Compress,Decompress |
| `compression.zstd` | compiles | missing-native: _zstd |
| `compression.zstd._zstdfile` | compiles | missing-native: _zstd |
| `concurrent.futures` | imports | smoke-mismatch: names differ: missing InterpreterPoolExecutor; extra none |
| `concurrent.futures.interpreter` | compiles | missing-native: _interpreters |
| `concurrent.futures.process` | compiles | missing-native: _socket |
| `concurrent.interpreters` | compiles | missing-native: _interpreters |
| `concurrent.interpreters._crossinterp` | compiles | missing-native: _interpreters |
| `concurrent.interpreters._queues` | compiles | missing-native: _interpreters |
| `csv` | compiles | missing-native: _csv |
| `ctypes` | compiles | missing-native: _ctypes |
| `ctypes._aix` | compiles | missing-native: _ctypes |
| `ctypes._endian` | compiles | missing-native: _ctypes |
| `ctypes._layout` | compiles | missing-native: _ctypes |
| `ctypes.macholib` | compiles | missing-native: _ctypes |
| `ctypes.macholib.dyld` | compiles | missing-native: _ctypes |
| `ctypes.macholib.dylib` | compiles | missing-native: _ctypes |
| `ctypes.macholib.framework` | compiles | missing-native: _ctypes |
| `ctypes.util` | compiles | missing-native: _ctypes |
| `ctypes.wintypes` | compiles | missing-native: _ctypes |
| `curses` | compiles | missing-native: _curses |
| `curses.ascii` | compiles | missing-native: _curses |
| `curses.has_key` | compiles | missing-native: _curses |
| `curses.panel` | compiles | missing-native: _curses |
| `curses.textpad` | compiles | missing-native: _curses |
| `dataclasses` | imports | smoke-mismatch: check differs: jvm "!AttributeError: type object 'D' has no attribute '__doc__'" vs reference 'D(x=1)' |
| `dbm.gnu` | compiles | missing-native: _gdbm |
| `dbm.ndbm` | compiles | missing-native: _dbm |
| `dbm.sqlite3` | compiles | missing-native: _sqlite3 |
| `doctest` | compiles | runtime-error: KeyError: '__mro__' |
| `email.mime.application` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.audio` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.base` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.image` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.message` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.multipart` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.nonmultipart` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.mime.text` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `email.policy` | compiles | runtime-error: TypeError: __py_nested_0___new__() missing required positional argument: 'name' |
| `encodings.big5` | compiles | missing-native: _codecs_tw |
| `encodings.big5hkscs` | compiles | missing-native: _codecs_hk |
| `encodings.bz2_codec` | compiles | missing-native: _bz2 |
| `encodings.cp932` | compiles | missing-native: _codecs_jp |
| `encodings.cp949` | compiles | missing-native: _codecs_kr |
| `encodings.cp950` | compiles | missing-native: _codecs_tw |
| `encodings.euc_jis_2004` | compiles | missing-native: _codecs_jp |
| `encodings.euc_jisx0213` | compiles | missing-native: _codecs_jp |
| `encodings.euc_jp` | compiles | missing-native: _codecs_jp |
| `encodings.euc_kr` | compiles | missing-native: _codecs_kr |
| `encodings.gb18030` | compiles | missing-native: _codecs_cn |
| `encodings.gb2312` | compiles | missing-native: _codecs_cn |
| `encodings.gbk` | compiles | missing-native: _codecs_cn |
| `encodings.hz` | compiles | missing-native: _codecs_cn |
| `encodings.idna` | compiles | missing-native: unicodedata |
| `encodings.iso2022_jp` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_1` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_2` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_2004` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_3` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_jp_ext` | compiles | missing-native: _codecs_iso2022 |
| `encodings.iso2022_kr` | compiles | missing-native: _codecs_iso2022 |
| `encodings.johab` | compiles | missing-native: _codecs_kr |
| `encodings.mbcs` | compiles | missing-native: codecs.mbcs_encode |
| `encodings.oem` | compiles | missing-native: codecs.oem_encode |
| `encodings.shift_jis` | compiles | missing-native: _codecs_jp |
| `encodings.shift_jis_2004` | compiles | missing-native: _codecs_jp |
| `encodings.shift_jisx0213` | compiles | missing-native: _codecs_jp |
| `encodings.utf_7` | compiles | runtime-error: AttributeError: module 'codecs' has no attribute 'utf_7_encode' |
| `ensurepip` | compiles | missing-native: _posixsubprocess |
| `ensurepip.__main__` | compiles | missing-native: _posixsubprocess |
| `ensurepip._uninstall` | compiles | missing-native: _posixsubprocess |
| `enum` | imports | smoke-mismatch: check differs: jvm "!AttributeError: 'Color' object has no attribute '__init__'" vs reference '[<Color.RED: 1>, <Color.GREEN: 2>]' |
| `ftplib` | compiles | missing-native: _socket |
| `hashlib` | imports | smoke-mismatch: names differ: missing pbkdf2_hmac,scrypt; extra none |
| `hmac` | compiles | missing-native: _operator |
| `http.client` | compiles | missing-native: _socket |
| `http.cookiejar` | compiles | missing-native: _socket |
| `http.server` | compiles | missing-native: _socket |
| `imaplib` | compiles | runtime-error: RuntimeError: Duplicate method name "run" with signature "(Ljava.lang.Object;Ljava.lang.Object;)Ljava.lang.Object;" in class file pyjvm315/r |
| `importlib.metadata` | unsupported | compiler-gap: import from module '_collections' is not available |
| `importlib.metadata._adapters` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._collections` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._context` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._functools` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._itertools` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._meta` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._text` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata._typing` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.metadata.diagnose` | compiles | runtime-error: ModuleNotFoundError: No module named 'importlib.metadata' |
| `importlib.readers` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources._adapters` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources._common` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources._functional` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources._itertools` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources.abc` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources.readers` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.resources.simple` | compiles | runtime-error: KeyError: '__mro__' |
| `importlib.simple` | compiles | runtime-error: KeyError: '__mro__' |
| `inspect` | compiles | runtime-error: KeyError: '__mro__' |
| `logging.config` | compiles | missing-native: _socket |
| `logging.handlers` | compiles | missing-native: _socket |
| `lzma` | compiles | missing-native: _lzma |
| `mailbox` | compiles | runtime-error: KeyError: '__main__' |
| `multiprocessing` | compiles | missing-native: _socket |
| `multiprocessing.connection` | compiles | missing-native: _socket |
| `multiprocessing.context` | compiles | missing-native: _socket |
| `multiprocessing.dummy` | compiles | missing-native: _socket |
| `multiprocessing.dummy.connection` | compiles | missing-native: _socket |
| `multiprocessing.forkserver` | compiles | missing-native: _socket |
| `multiprocessing.heap` | compiles | missing-native: _socket |
| `multiprocessing.managers` | compiles | missing-native: _socket |
| `multiprocessing.pool` | compiles | missing-native: _socket |
| `multiprocessing.popen_fork` | compiles | missing-native: _socket |
| `multiprocessing.popen_forkserver` | compiles | missing-native: _socket |
| `multiprocessing.popen_spawn_posix` | compiles | missing-native: _socket |
| `multiprocessing.popen_spawn_win32` | compiles | missing-native: _socket |
| `multiprocessing.process` | compiles | missing-native: _socket |
| `multiprocessing.queues` | compiles | missing-native: _socket |
| `multiprocessing.reduction` | compiles | missing-native: _socket |
| `multiprocessing.resource_sharer` | compiles | missing-native: _socket |
| `multiprocessing.resource_tracker` | compiles | missing-native: _socket |
| `multiprocessing.shared_memory` | compiles | missing-native: _socket |
| `multiprocessing.sharedctypes` | compiles | missing-native: _socket |
| `multiprocessing.spawn` | compiles | missing-native: _socket |
| `multiprocessing.synchronize` | compiles | missing-native: _socket |
| `multiprocessing.util` | compiles | missing-native: _socket |
| `os` | imports | smoke-mismatch: names differ: missing AT_NO_AUTOMOUNT,CLD_CONTINUED,CLD_DUMPED,CLD_EXITED,CLD_KILLED,CLD_STOPPED...; extra none |
| `pathlib._os` | imports | smoke-mismatch: names differ: missing EHWPOISON; extra fcntl |
| `pathlib.types` | compiles | missing-native: _typing |
| `pdb` | compiles | runtime-error: KeyError: '__mro__' |
| `pickle` | imports | smoke-mismatch: names differ: missing PickleBuffer; extra none |
| `pickletools` | compiles | runtime-error: TypeError: '<=' not supported between instances of 'dict_view' and 'dict_view' |
| `plistlib` | compiles | missing-native: pyexpat |
| `poplib` | compiles | missing-native: _socket |
| `pprint` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `profiling.sampling` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.__main__` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling._child_monitor` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling._css_utils` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling._format_utils` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling._sync_coordinator` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.binary_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.binary_reader` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.cli` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.constants` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.dump` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.errors` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.gecko_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.heatmap_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.jsonl_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector.collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector.constants` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector.display` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector.trend_tracker` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.live_collector.widgets` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.module_utils` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.opcode_utils` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.pstats_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.sample` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.stack_collector` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.sampling.string_table` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `profiling.tracing` | compiles | missing-native: _lsprof |
| `profiling.tracing.__main__` | compiles | missing-native: _lsprof |
| `profiling.tracing._utils` | compiles | missing-native: _lsprof |
| `pstats` | compiles | runtime-error: AttributeError: type object 'FunctionProfile' has no attribute '__doc__' |
| `pty` | compiles | missing-native: termios |
| `py_compile` | compiles | runtime-error: AttributeError: 'PycInvalidationMode' object has no attribute '__init__' |
| `pydoc` | compiles | runtime-error: KeyError: '__mro__' |
| `pydoc_data.topics` | unsupported | compiler-crash: ValueError: JVM UTF8 constant exceeds 65535 bytes |
| `rlcompleter` | compiles | runtime-error: KeyError: '__mro__' |
| `secrets` | compiles | missing-native: _operator |
| `selectors` | imports | smoke-mismatch: names differ: missing EpollSelector; extra none |
| `shlex` | imports | smoke-mismatch: check differs: jvm '!TypeError: method takes no keyword arguments' vs reference '([\'a\', \'b c\', \'d e\'], \'\\\\\'it\\\\\'"\\\\\'"\\\\\'s\\\\\'\')' |
| `shutil` | imports | smoke-mismatch: names differ: missing disk_usage; extra none |
| `signal` | imports | smoke-mismatch: names differ: missing pidfd_send_signal,sigtimedwait,sigwaitinfo,struct_siginfo; extra none |
| `site` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'copyright' |
| `smtplib` | compiles | missing-native: _socket |
| `socket` | compiles | missing-native: _socket |
| `socketserver` | compiles | missing-native: _socket |
| `sqlite3` | compiles | missing-native: _sqlite3 |
| `sqlite3.__main__` | compiles | missing-native: _sqlite3 |
| `sqlite3._completer` | compiles | missing-native: _sqlite3 |
| `sqlite3.dbapi2` | compiles | missing-native: _sqlite3 |
| `sqlite3.dump` | compiles | missing-native: _sqlite3 |
| `ssl` | compiles | missing-native: _ssl |
| `statistics` | compiles | missing-native: math.sumprod |
| `stringprep` | compiles | missing-native: unicodedata |
| `subprocess` | compiles | missing-native: _posixsubprocess |
| `symtable` | compiles | missing-native: _symtable |
| `tarfile` | compiles | runtime-error: TypeError: __slots__ must be a string or iterable of strings |
| `tkinter` | compiles | missing-native: _tkinter |
| `tkinter.__main__` | compiles | missing-native: _tkinter |
| `tkinter.colorchooser` | compiles | missing-native: _tkinter |
| `tkinter.commondialog` | compiles | missing-native: _tkinter |
| `tkinter.constants` | compiles | missing-native: _tkinter |
| `tkinter.dialog` | compiles | missing-native: _tkinter |
| `tkinter.dnd` | compiles | missing-native: _tkinter |
| `tkinter.filedialog` | compiles | missing-native: _tkinter |
| `tkinter.font` | compiles | missing-native: _tkinter |
| `tkinter.messagebox` | compiles | missing-native: _tkinter |
| `tkinter.scrolledtext` | compiles | missing-native: _tkinter |
| `tkinter.simpledialog` | compiles | missing-native: _tkinter |
| `tkinter.ttk` | compiles | missing-native: _tkinter |
| `tomllib` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'getrecursionlimit' |
| `tomllib._parser` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'getrecursionlimit' |
| `tomllib._re` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'getrecursionlimit' |
| `tomllib._types` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'getrecursionlimit' |
| `trace` | compiles | runtime-error: KeyError: '__mro__' |
| `tracemalloc` | compiles | missing-native: _tracemalloc |
| `tty` | compiles | missing-native: termios |
| `turtle` | compiles | missing-native: _tkinter |
| `types` | imports | smoke-mismatch: names differ: missing CapsuleType,LazyImportType; extra none; check differs: jvm '!TypeError: implementation() takes no arguments' vs reference "(namespace(a=1), 'function', 'mappingproxy')" |
| `typing` | compiles | missing-native: _typing |
| `unittest` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.__main__` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest._log` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.async_case` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.case` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.loader` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.main` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.mock` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.result` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.runner` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.signals` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.suite` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `unittest.util` | compiles | runtime-error: AttributeError: 'PyBuiltinType' object has no attribute '__repr__' |
| `urllib.parse` | imports | smoke-mismatch: check differs: jvm '!KeyError: 97' vs reference "(ParseResult(scheme='http', netloc='u:p@h:80', path='/a/b', params=''," |
| `urllib.request` | compiles | missing-native: _socket |
| `urllib.robotparser` | compiles | missing-native: _socket |
| `uuid` | compiles | runtime-error: AttributeError: 'SafeUUID' object has no attribute '__init__' |
| `venv` | compiles | missing-native: _posixsubprocess |
| `venv.__main__` | compiles | missing-native: _posixsubprocess |
| `webbrowser` | compiles | missing-native: _posixsubprocess |
| `wsgiref.simple_server` | compiles | missing-native: _socket |
| `wsgiref.types` | compiles | missing-native: _typing |
| `xml.dom.expatbuilder` | compiles | missing-native: pyexpat |
| `xml.parsers.expat` | compiles | missing-native: pyexpat |
| `xml.sax.expatreader` | compiles | runtime-error: SAXReaderNotAvailable: expat not supported |
| `xml.sax.saxutils` | compiles | missing-native: _socket |
| `xmlrpc.client` | compiles | missing-native: _socket |
| `xmlrpc.server` | compiles | missing-native: _socket |
| `zoneinfo` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._common` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._tzpath` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |
| `zoneinfo._zoneinfo` | compiles | runtime-error: AttributeError: module 'sys' has no attribute 'abiflags' |

</details>
