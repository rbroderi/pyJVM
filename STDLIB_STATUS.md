# Standard library status

Generated from `tools/stdlib_manifest.json` by `python3 tools/stdlib_status.py report`; do not edit by hand.
The manifest records, per module and per language lane, how far the CPython `Lib` source gets on pyJVM

- **smoke**: compiles, imports from the stdlib jar on the JVM, and its public names and a short behavioural check match the reference interpreter
- **imports**: imports from the jar, but the names or the behavioural check differ from the reference (reason given)
- **compiles**: compiles to a class file, but the import fails on the JVM (reason given)
- **unsupported**: the compiler rejects it (reason given)
- **excluded**: not part of the build (test suites, IDLE and the like)

## Windows host

The manifest below was measured on a POSIX host (Linux, Ubuntu 24.04 in WSL, glibc 2.39), where descriptors, pipes, `select`/`poll`/`epoll` and signals during blocking I/O are real kernel facilities (N0) and processes are created with `posix_spawn` (N1: `_posixsubprocess`, `os.posix_spawn`, the wait family, `os.kill`, `os.system`), but `_socket` is still an importable shell without sockets. On Windows the runtime has a real Winsock `_socket` (stream sockets, IPv4/IPv6; no datagram calls and no name resolution, so `getaddrinfo`-based calls such as `create_connection` and `asyncio.create_server` raise AttributeError), `_overlapped` (completion ports, events, waits, named pipes, `WSARecv`/`WSARecvInto`/`WSASend`; the `AcceptEx`/`ConnectEx`/`DisconnectEx`/`TransmitFile`/`WSAConnect`/`BindLocal`/`WSARecvFrom*`/`WSASendTo` names are absent), `select.select` on sockets and `signal.set_wakeup_fd` with a socket. With these `asyncio` imports and `asyncio.run` works on the ProactorEventLoop (timers, tasks, threads and executors, subprocess pipes, named-pipe servers and clients).

## Lanes

| Lane | Reference interpreter | Library | Modules |
|---|---|---|---|
| 3.13 | Python 3.13.18 (main, Oct 26 2026, 14:08:20) [Clang 23.1.3 ] | the interpreter's own Lib | 548 |
| 3.15 | Python 3.15.2 (main, Oct 26 2026, 14:06:56) [Clang 23.1.3 ] | the interpreter's own Lib | 609 |

## Module counts by status

| Status | 3.13 | 3.15 |
|---|---|---|
| smoke | 433 | 448 |
| imports | 30 | 34 |
| compiles | 85 | 127 |
| unsupported | 0 | 0 |
| **total** | **548** | **609** |

Excluded directories: `__phello__` (frozen-module test package), `idlelib` (IDLE (needs tkinter)), `site-packages` (not part of the library), `turtledemo` (turtle demo programs (need tkinter)).

## Top blockers, lane 3.13

| Modules | Blocker | Examples |
|---|---|---|
| 30 | smoke-mismatch | `_colorize`, `_pyrepl._threading_handler`, `_pyrepl.commands`, `_pyrepl.console`, `_pyrepl.pager`, ... |
| 14 | missing-native: _ctypes | `_pyrepl._minimal_curses`, `_pyrepl.curses`, `_pyrepl.unix_console`, `_pyrepl.unix_eventqueue`, `ctypes`, ... |
| 14 | missing-native: _tkinter | `tkinter`, `tkinter.__main__`, `tkinter.colorchooser`, `tkinter.commondialog`, `tkinter.constants`, ... |
| 9 | missing-native: unicodedata | `_pyrepl.completing_reader`, `_pyrepl.historical_reader`, `_pyrepl.input`, `_pyrepl.reader`, `_pyrepl.readline`, ... |
| 7 | runtime-error: LookupError: no such codec is supported. | `encodings.iso2022_jp`, `encodings.iso2022_jp_1`, `encodings.iso2022_jp_2`, `encodings.iso2022_jp_2004`, `encodings.iso2022_jp_3`, ... |
| 5 | missing-native: _curses | `curses`, `curses.ascii`, `curses.has_key`, `curses.panel`, `curses.textpad` |
| 5 | missing-native: _sqlite3 | `dbm.sqlite3`, `sqlite3`, `sqlite3.__main__`, `sqlite3.dbapi2`, `sqlite3.dump` |
| 5 | missing-native: pyexpat | `plistlib`, `xml.dom.expatbuilder`, `xml.parsers.expat`, `xmlrpc.client`, `xmlrpc.server` |
| 3 | missing-native: _multiprocessing | `multiprocessing.forkserver`, `multiprocessing.resource_tracker`, `multiprocessing.synchronize` |
| 2 | missing-native: _bz2 | `bz2`, `encodings.bz2_codec` |
| 2 | missing-native: mmap | `multiprocessing.heap`, `multiprocessing.shared_memory` |
| 2 | missing-native: msvcrt | `_pyrepl.windows_console`, `multiprocessing.popen_spawn_win32` |
| 2 | runtime-error: ImportError: win32 only | `asyncio.windows_events`, `asyncio.windows_utils` |
| 1 | missing-native: _csv | `csv` |
| 1 | missing-native: _dbm | `dbm.ndbm` |
| 1 | missing-native: _gdbm | `dbm.gnu` |
| 1 | missing-native: _lsprof | `cProfile` |
| 1 | missing-native: _lzma | `lzma` |
| 1 | missing-native: _ssl | `ssl` |
| 1 | missing-native: _symtable | `symtable` |
| 1 | missing-native: _tracemalloc | `tracemalloc` |
| 1 | missing-native: codecs.mbcs_encode | `encodings.mbcs` |
| 1 | missing-native: codecs.oem_encode | `encodings.oem` |
| 1 | runtime-error: AttributeError: module 'codecs' has no attribute 'utf_7_encode' | `encodings.utf_7` |
| 1 | runtime-error: ImportError: No support for sending fds between processes | `multiprocessing.popen_forkserver` |
| 3 | 3 further reasons | |

## Top blockers, lane 3.15

| Modules | Blocker | Examples |
|---|---|---|
| 34 | smoke-mismatch | `_ast_unparse`, `_pyrepl._module_completer`, `_pyrepl._threading_handler`, `_pyrepl.fancycompleter`, `_pyrepl.pager`, ... |
| 18 | missing-native: unicodedata | `_pyrepl.base_eventqueue`, `_pyrepl.commands`, `_pyrepl.completing_reader`, `_pyrepl.console`, `_pyrepl.content`, ... |
| 14 | missing-native: _tkinter | `tkinter`, `tkinter.__main__`, `tkinter.colorchooser`, `tkinter.commondialog`, `tkinter.constants`, ... |
| 12 | missing-native: _ctypes | `_pyrepl.windows_console`, `ctypes`, `ctypes._aix`, `ctypes._endian`, `ctypes._layout`, ... |
| 11 | missing-native: _curses | `curses`, `curses.ascii`, `curses.has_key`, `curses.panel`, `curses.textpad`, ... |
| 10 | runtime-error: AttributeError: function has no attribute '__builtins__' | `importlib.metadata`, `importlib.metadata._adapters`, `importlib.metadata._collections`, `importlib.metadata._context`, `importlib.metadata._functools`, ... |
| 8 | missing-native: _remote_debugging | `asyncio.__main__`, `asyncio.tools`, `profiling.sampling.__main__`, `profiling.sampling._child_monitor`, `profiling.sampling.binary_collector`, ... |
| 7 | runtime-error: LookupError: no such codec is supported. | `encodings.iso2022_jp`, `encodings.iso2022_jp_1`, `encodings.iso2022_jp_2`, `encodings.iso2022_jp_2004`, `encodings.iso2022_jp_3`, ... |
| 6 | missing-native: _sqlite3 | `dbm.sqlite3`, `sqlite3`, `sqlite3.__main__`, `sqlite3._completer`, `sqlite3.dbapi2`, ... |
| 5 | missing-native: pyexpat | `plistlib`, `xml.dom.expatbuilder`, `xml.parsers.expat`, `xmlrpc.client`, `xmlrpc.server` |
| 4 | missing-native: _interpreters | `concurrent.futures.interpreter`, `concurrent.interpreters`, `concurrent.interpreters._crossinterp`, `concurrent.interpreters._queues` |
| 4 | missing-native: _lsprof | `cProfile`, `profiling.tracing`, `profiling.tracing.__main__`, `profiling.tracing._utils` |
| 3 | missing-native: _bz2 | `bz2`, `compression.bz2`, `encodings.bz2_codec` |
| 3 | missing-native: _multiprocessing | `multiprocessing.forkserver`, `multiprocessing.resource_tracker`, `multiprocessing.synchronize` |
| 2 | missing-native: _lzma | `compression.lzma`, `lzma` |
| 2 | missing-native: _zstd | `compression.zstd`, `compression.zstd._zstdfile` |
| 2 | missing-native: mmap | `multiprocessing.heap`, `multiprocessing.shared_memory` |
| 2 | runtime-error: ImportError: win32 only | `asyncio.windows_events`, `asyncio.windows_utils` |
| 1 | missing-native: _csv | `csv` |
| 1 | missing-native: _dbm | `dbm.ndbm` |
| 1 | missing-native: _gdbm | `dbm.gnu` |
| 1 | missing-native: _ssl | `ssl` |
| 1 | missing-native: _symtable | `symtable` |
| 1 | missing-native: _tracemalloc | `tracemalloc` |
| 1 | missing-native: codecs.mbcs_encode | `encodings.mbcs` |
| 7 | 7 further reasons | |

## Modules that are not at `smoke`

<details><summary>Lane 3.13: 115 modules</summary>

| Module | Status | Reason |
|---|---|---|
| `_colorize` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl._minimal_curses` | compiles | missing-native: _ctypes |
| `_pyrepl._threading_handler` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.commands` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.completing_reader` | compiles | missing-native: unicodedata |
| `_pyrepl.console` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.curses` | compiles | missing-native: _ctypes |
| `_pyrepl.historical_reader` | compiles | missing-native: unicodedata |
| `_pyrepl.input` | compiles | missing-native: unicodedata |
| `_pyrepl.pager` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.reader` | compiles | missing-native: unicodedata |
| `_pyrepl.readline` | compiles | missing-native: unicodedata |
| `_pyrepl.simple_interact` | compiles | missing-native: unicodedata |
| `_pyrepl.trace` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.unix_console` | compiles | missing-native: _ctypes |
| `_pyrepl.unix_eventqueue` | compiles | missing-native: _ctypes |
| `_pyrepl.utils` | compiles | missing-native: unicodedata |
| `_pyrepl.windows_console` | compiles | missing-native: msvcrt |
| `ast` | imports | smoke-mismatch: names differ: missing IntEnum,auto,contextmanager,main,nullcontext,unparse; extra Interpolation,TemplateStr,compare |
| `asyncio.sslproto` | imports | smoke-mismatch: names differ: missing SSLAgainErrors; extra ssl |
| `asyncio.windows_events` | compiles | runtime-error: ImportError: win32 only |
| `asyncio.windows_utils` | compiles | runtime-error: ImportError: win32 only |
| `bz2` | compiles | missing-native: _bz2 |
| `cProfile` | compiles | missing-native: _lsprof |
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
| `dbm.gnu` | compiles | missing-native: _gdbm |
| `dbm.ndbm` | compiles | missing-native: _dbm |
| `dbm.sqlite3` | compiles | missing-native: _sqlite3 |
| `encodings.bz2_codec` | compiles | missing-native: _bz2 |
| `encodings.idna` | compiles | missing-native: unicodedata |
| `encodings.iso2022_jp` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_1` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_2` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_2004` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_3` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_ext` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_kr` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.mbcs` | compiles | missing-native: codecs.mbcs_encode |
| `encodings.oem` | compiles | missing-native: codecs.oem_encode |
| `encodings.utf_7` | compiles | runtime-error: AttributeError: module 'codecs' has no attribute 'utf_7_encode' |
| `ftplib` | imports | smoke-mismatch: names differ: missing FTP_TLS; extra none |
| `hashlib` | imports | smoke-mismatch: names differ: missing pbkdf2_hmac; extra none |
| `http.client` | imports | smoke-mismatch: names differ: missing HTTPSConnection; extra none |
| `imaplib` | imports | smoke-mismatch: names differ: missing IMAP4_SSL; extra none |
| `importlib.metadata._meta` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `importlib.resources.readers` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `inspect` | imports | smoke-mismatch: check differs: jvm '!TypeError: object is not sliceable: NoneType' vs reference "('(a, b=2, *c, d, **e)', False, 'Get useful i')" |
| `lzma` | compiles | missing-native: _lzma |
| `multiprocessing.forkserver` | compiles | missing-native: _multiprocessing |
| `multiprocessing.heap` | compiles | missing-native: mmap |
| `multiprocessing.managers` | imports | smoke-mismatch: names differ: missing SharedMemoryManager; extra none |
| `multiprocessing.popen_forkserver` | compiles | runtime-error: ImportError: No support for sending fds between processes |
| `multiprocessing.popen_spawn_win32` | compiles | missing-native: msvcrt |
| `multiprocessing.resource_tracker` | compiles | missing-native: _multiprocessing |
| `multiprocessing.shared_memory` | compiles | missing-native: mmap |
| `multiprocessing.sharedctypes` | compiles | missing-native: _ctypes |
| `multiprocessing.synchronize` | compiles | missing-native: _multiprocessing |
| `os` | imports | smoke-mismatch: names differ: missing CLONE_FILES,CLONE_FS,CLONE_NEWIPC,CLONE_NEWNET,CLONE_NEWNS,CLONE_NEWPID...; extra none |
| `pickle` | imports | smoke-mismatch: names differ: missing PickleBuffer; extra none |
| `plistlib` | compiles | missing-native: pyexpat |
| `poplib` | imports | smoke-mismatch: names differ: missing POP3_SSL; extra none |
| `pprint` | imports | smoke-mismatch: check differs: jvm '!RecursionError: maximum recursion depth exceeded' vs reference "{'k': [0,\\n       1,\\n       2,\\n       3,\\n       4,\\n       5,\\n    " |
| `shutil` | imports | smoke-mismatch: names differ: missing disk_usage; extra none |
| `signal` | imports | smoke-mismatch: names differ: missing pidfd_send_signal,sigtimedwait,sigwaitinfo,struct_siginfo; extra none |
| `smtplib` | imports | smoke-mismatch: names differ: missing SMTP_SSL; extra none |
| `socket` | imports | smoke-mismatch: names differ: missing AF_ALG,AF_APPLETALK,AF_ASH,AF_ATMPVC,AF_ATMSVC,AF_AX25...; extra none |
| `socketserver` | imports | smoke-mismatch: names differ: missing ForkingMixIn,ForkingTCPServer,ForkingUDPServer,ForkingUnixDatagramServer,ForkingUnixStreamServer; extra none |
| `sqlite3` | compiles | missing-native: _sqlite3 |
| `sqlite3.__main__` | compiles | missing-native: _sqlite3 |
| `sqlite3.dbapi2` | compiles | missing-native: _sqlite3 |
| `sqlite3.dump` | compiles | missing-native: _sqlite3 |
| `ssl` | compiles | missing-native: _ssl |
| `stringprep` | compiles | missing-native: unicodedata |
| `symtable` | compiles | missing-native: _symtable |
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
| `tomllib._parser` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `tomllib._re` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `tracemalloc` | compiles | missing-native: _tracemalloc |
| `turtle` | compiles | missing-native: _tkinter |
| `types` | imports | smoke-mismatch: check differs: jvm "(namespace(a=1), 'function', 'dict')" vs reference "(namespace(a=1), 'function', 'mappingproxy')" |
| `unittest.__main__` | compiles | runtime-error: SystemExit: 5 |
| `urllib.parse` | imports | smoke-mismatch: check differs: jvm '!KeyError: 97' vs reference "(ParseResult(scheme='http', netloc='u:p@h:80', path='/a/b', params=''," |
| `urllib.request` | imports | smoke-mismatch: names differ: missing HTTPSHandler; extra none |
| `venv.__main__` | compiles | runtime-error: SystemExit: 2 |
| `xml.dom.expatbuilder` | compiles | missing-native: pyexpat |
| `xml.parsers.expat` | compiles | missing-native: pyexpat |
| `xml.sax.expatreader` | compiles | runtime-error: SAXReaderNotAvailable: expat not supported |
| `xmlrpc.client` | compiles | missing-native: pyexpat |
| `xmlrpc.server` | compiles | missing-native: pyexpat |

</details>

<details><summary>Lane 3.15: 161 modules</summary>

| Module | Status | Reason |
|---|---|---|
| `_ast_unparse` | imports | smoke-mismatch: names differ: missing PyCF_ALLOW_TOP_LEVEL_AWAIT,PyCF_TYPE_COMMENTS; extra AugLoad,AugStore,ExtSlice,Index... |
| `_pyrepl._module_completer` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl._threading_handler` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.base_eventqueue` | compiles | missing-native: unicodedata |
| `_pyrepl.commands` | compiles | missing-native: unicodedata |
| `_pyrepl.completing_reader` | compiles | missing-native: unicodedata |
| `_pyrepl.console` | compiles | missing-native: unicodedata |
| `_pyrepl.content` | compiles | missing-native: unicodedata |
| `_pyrepl.fancycompleter` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.historical_reader` | compiles | missing-native: unicodedata |
| `_pyrepl.input` | compiles | missing-native: unicodedata |
| `_pyrepl.layout` | compiles | missing-native: unicodedata |
| `_pyrepl.pager` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.reader` | compiles | missing-native: unicodedata |
| `_pyrepl.readline` | compiles | missing-native: unicodedata |
| `_pyrepl.render` | compiles | missing-native: unicodedata |
| `_pyrepl.simple_interact` | compiles | missing-native: unicodedata |
| `_pyrepl.trace` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `_pyrepl.unix_console` | compiles | missing-native: unicodedata |
| `_pyrepl.unix_eventqueue` | compiles | missing-native: unicodedata |
| `_pyrepl.utils` | compiles | missing-native: unicodedata |
| `_pyrepl.windows_console` | compiles | missing-native: _ctypes |
| `_pyrepl.windows_eventqueue` | compiles | missing-native: unicodedata |
| `ast` | imports | smoke-mismatch: names differ: missing main,unparse; extra none |
| `asyncio.__main__` | compiles | missing-native: _remote_debugging |
| `asyncio.sslproto` | imports | smoke-mismatch: names differ: missing SSLAgainErrors; extra ssl |
| `asyncio.tools` | compiles | missing-native: _remote_debugging |
| `asyncio.windows_events` | compiles | runtime-error: ImportError: win32 only |
| `asyncio.windows_utils` | compiles | runtime-error: ImportError: win32 only |
| `base64` | imports | smoke-mismatch: check differs: jvm "!AttributeError: module 'binascii' has no attribute 'b2a_base32'" vs reference "(b'aGVsbG8gd29ybGQ=', b'hi', b'MFRA====', b'FF')" |
| `bz2` | compiles | missing-native: _bz2 |
| `cProfile` | compiles | missing-native: _lsprof |
| `compression.bz2` | compiles | missing-native: _bz2 |
| `compression.lzma` | compiles | missing-native: _lzma |
| `compression.zlib` | imports | smoke-mismatch: names differ: missing none; extra Compress,Decompress |
| `compression.zstd` | compiles | missing-native: _zstd |
| `compression.zstd._zstdfile` | compiles | missing-native: _zstd |
| `concurrent.futures` | imports | smoke-mismatch: names differ: missing InterpreterPoolExecutor; extra none |
| `concurrent.futures.interpreter` | compiles | missing-native: _interpreters |
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
| `dbm.gnu` | compiles | missing-native: _gdbm |
| `dbm.ndbm` | compiles | missing-native: _dbm |
| `dbm.sqlite3` | compiles | missing-native: _sqlite3 |
| `encodings.bz2_codec` | compiles | missing-native: _bz2 |
| `encodings.idna` | compiles | missing-native: unicodedata |
| `encodings.iso2022_jp` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_1` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_2` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_2004` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_3` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_jp_ext` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.iso2022_kr` | compiles | runtime-error: LookupError: no such codec is supported. |
| `encodings.mbcs` | compiles | missing-native: codecs.mbcs_encode |
| `encodings.oem` | compiles | missing-native: codecs.oem_encode |
| `encodings.utf_7` | compiles | runtime-error: AttributeError: module 'codecs' has no attribute 'utf_7_encode' |
| `ftplib` | imports | smoke-mismatch: names differ: missing FTP_TLS; extra none |
| `hashlib` | imports | smoke-mismatch: names differ: missing pbkdf2_hmac,scrypt; extra none |
| `http.client` | imports | smoke-mismatch: names differ: missing HTTPSConnection; extra none |
| `imaplib` | imports | smoke-mismatch: names differ: missing IMAP4_SSL; extra none |
| `importlib.metadata` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._adapters` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._collections` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._context` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._functools` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._itertools` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._meta` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._text` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata._typing` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.metadata.diagnose` | compiles | runtime-error: AttributeError: function has no attribute '__builtins__' |
| `importlib.resources.readers` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `inspect` | imports | smoke-mismatch: check differs: jvm '!TypeError: object is not sliceable: NoneType' vs reference "('(a, b=2, *c, d, **e)', False, 'Get useful i')" |
| `lzma` | compiles | missing-native: _lzma |
| `multiprocessing.forkserver` | compiles | missing-native: _multiprocessing |
| `multiprocessing.heap` | compiles | missing-native: mmap |
| `multiprocessing.managers` | imports | smoke-mismatch: names differ: missing SharedMemoryManager; extra none |
| `multiprocessing.popen_forkserver` | compiles | runtime-error: ImportError: No support for sending fds between processes |
| `multiprocessing.popen_spawn_win32` | compiles | missing-native: msvcrt |
| `multiprocessing.resource_tracker` | compiles | missing-native: _multiprocessing |
| `multiprocessing.shared_memory` | compiles | missing-native: mmap |
| `multiprocessing.sharedctypes` | compiles | missing-native: _ctypes |
| `multiprocessing.synchronize` | compiles | missing-native: _multiprocessing |
| `os` | imports | smoke-mismatch: names differ: missing AT_NO_AUTOMOUNT,CLONE_FILES,CLONE_FS,CLONE_NEWCGROUP,CLONE_NEWIPC,CLONE_NEWNET...; extra none |
| `pathlib._os` | imports | smoke-mismatch: names differ: missing EHWPOISON; extra none |
| `pickle` | imports | smoke-mismatch: names differ: missing PickleBuffer; extra none |
| `plistlib` | compiles | missing-native: pyexpat |
| `poplib` | imports | smoke-mismatch: names differ: missing POP3_SSL; extra none |
| `pprint` | imports | smoke-mismatch: check differs: jvm '!RecursionError: maximum recursion depth exceeded' vs reference "{'k': [0,\\n       1,\\n       2,\\n       3,\\n       4,\\n       5,\\n    " |
| `profiling.sampling.__main__` | compiles | missing-native: _remote_debugging |
| `profiling.sampling._child_monitor` | compiles | missing-native: _remote_debugging |
| `profiling.sampling.binary_collector` | compiles | missing-native: _remote_debugging |
| `profiling.sampling.binary_reader` | compiles | missing-native: _remote_debugging |
| `profiling.sampling.cli` | compiles | missing-native: _remote_debugging |
| `profiling.sampling.live_collector` | compiles | missing-native: _curses |
| `profiling.sampling.live_collector.collector` | compiles | missing-native: _curses |
| `profiling.sampling.live_collector.constants` | compiles | missing-native: _curses |
| `profiling.sampling.live_collector.display` | compiles | missing-native: _curses |
| `profiling.sampling.live_collector.trend_tracker` | compiles | missing-native: _curses |
| `profiling.sampling.live_collector.widgets` | compiles | missing-native: _curses |
| `profiling.sampling.sample` | compiles | missing-native: _remote_debugging |
| `profiling.tracing` | compiles | missing-native: _lsprof |
| `profiling.tracing.__main__` | compiles | missing-native: _lsprof |
| `profiling.tracing._utils` | compiles | missing-native: _lsprof |
| `shlex` | imports | smoke-mismatch: check differs: jvm '!TypeError: method takes no keyword arguments' vs reference '([\'a\', \'b c\', \'d e\'], \'\\\\\'it\\\\\'"\\\\\'"\\\\\'s\\\\\'\')' |
| `shutil` | imports | smoke-mismatch: names differ: missing disk_usage; extra none |
| `signal` | imports | smoke-mismatch: names differ: missing pidfd_send_signal,sigtimedwait,sigwaitinfo,struct_siginfo; extra none |
| `smtplib` | imports | smoke-mismatch: names differ: missing SMTP_SSL; extra none |
| `socket` | imports | smoke-mismatch: names differ: missing AF_ALG,AF_APPLETALK,AF_ASH,AF_ATMPVC,AF_ATMSVC,AF_AX25...; extra none |
| `socketserver` | imports | smoke-mismatch: names differ: missing ForkingMixIn,ForkingTCPServer,ForkingUDPServer,ForkingUnixDatagramServer,ForkingUnixStreamServer; extra none |
| `sqlite3` | compiles | missing-native: _sqlite3 |
| `sqlite3.__main__` | compiles | missing-native: _sqlite3 |
| `sqlite3._completer` | compiles | missing-native: _sqlite3 |
| `sqlite3.dbapi2` | compiles | missing-native: _sqlite3 |
| `sqlite3.dump` | compiles | missing-native: _sqlite3 |
| `ssl` | compiles | missing-native: _ssl |
| `stringprep` | compiles | missing-native: unicodedata |
| `symtable` | compiles | missing-native: _symtable |
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
| `tomllib._parser` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `tomllib._re` | imports | smoke-mismatch: names differ: missing annotations; extra none |
| `tracemalloc` | compiles | missing-native: _tracemalloc |
| `turtle` | compiles | missing-native: _tkinter |
| `types` | imports | smoke-mismatch: names differ: missing CapsuleType,LazyImportType; extra none; check differs: jvm "(namespace(a=1), 'function', 'dict')" vs reference "(namespace(a=1), 'function', 'mappingproxy')" |
| `unittest.__main__` | compiles | runtime-error: SystemExit: 5 |
| `urllib.parse` | imports | smoke-mismatch: check differs: jvm '!KeyError: 97' vs reference "(ParseResult(scheme='http', netloc='u:p@h:80', path='/a/b', params=''," |
| `urllib.request` | imports | smoke-mismatch: names differ: missing HTTPSHandler; extra none |
| `venv.__main__` | compiles | runtime-error: SystemExit: 2 |
| `xml.dom.expatbuilder` | compiles | missing-native: pyexpat |
| `xml.parsers.expat` | compiles | missing-native: pyexpat |
| `xml.sax.expatreader` | compiles | runtime-error: SAXReaderNotAvailable: expat not supported |
| `xmlrpc.client` | compiles | missing-native: pyexpat |
| `xmlrpc.server` | compiles | missing-native: pyexpat |

</details>
