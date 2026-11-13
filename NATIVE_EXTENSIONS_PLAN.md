# pyJVM: Easy Native Extensions in the Style of PyO3

Nov 11, 2026 · @m

## Summary

pyJVM gets two routes for native code, both resting on one handle-based ABI and on Java 25 as the minimum version. New code is written in Rust against a `pyjvm` crate that uses PyO3's macro names. Existing extensions, in C, Cython or PyO3, run through a shim that implements CPython's own C API on top of the same ABI.

| Route | For | What the author does | Cost at the boundary |
| --- | --- | --- | --- |
| Native Rust (`pyjvm` crate) | New extensions; ports that want speed | Writes Rust with `#[pyfunction]`, `#[pyclass]` | Lowest. Scalar and byte-slice functions are one downcall with no callbacks |
| C-API shim, unmodified `abi3` binaries | Existing wheels built for CPython's stable ABI, whatever their source language | Nothing, or `pyjvm-ext repack` | High. Every object crossing gets a `PyObject*` stub and emulated reference counts |
| C-API shim, rebuilt from source | Other C and Cython extensions, and new C code | Recompiles against pyJVM's `Python.h` | High, as above |

Not covered: version-specific CPython binaries (`cp315-cp315` wheels) loaded as they are. They depend on CPython's internal struct layouts and must be rebuilt from source, as on PyPy and GraalPy.

The plan has five tracks:

1. **Java 25 (R0).** Re-target the compiler, runtime and CI from Java 21 to Java 25.
2. **Runtime foundation (R1).** Internal Java work: extension registry, descriptor format, conversions, class slots.
3. **ABI and Rust crates (R2).** The handle ABI over the Foreign Function and Memory API (FFM), and the `pyjvm`, `pyjvm-macros` and `pyjvm-sys` crates.
4. **C-API shim (C).** `libpyjvm-capi`, written in Rust on `pyjvm-sys`, exporting CPython's C symbols, plus pyJVM's copy of the CPython headers.
5. **Tooling (R3).** `pyjvm-ext`: scaffold, build, test, package, repack existing wheels.

What this revision changes:

- **Java 25 is decided.** FFM is final there, so the bridge lives in the core runtime jar and the separate native jar is gone.
- **Existing extensions are now a goal, not a later spike.** The shim is the largest piece of the plan by a wide margin: the limited API alone is several hundred functions, and it leans on Phase 2 of the [roadmap](https://claude.ai/artifact/G68GJ9aARaodNdkzazvb6i) for strings, buffers, subclassing and threads.
- **The Rust route stays first.** The shim is built on its ABI, and it remains the fast path.

Decisions still yours, detailed under Risks and open questions:

- Whether the shim holds a global lock for extensions that do not declare themselves thread-safe (recommended: yes, as CPython's free-threaded build does).
- How far the shim goes: stable ABI only, or the full API when rebuilt from source (recommended: both, in that order).
- Whether NumPy is a committed target (recommended: no; a feasibility spike after the shim's other milestones).
- Licence for the crates and the shipped headers (recommended: linking exception for the crates; headers stay under the PSF licence).

## Where pyJVM stands today

pyJVM has native modules but no extension API: each one is hand-wired into the runtime, and nothing outside the `pyjvm315.runtime` package can add one. Findings are from the repository at commit `cfa9178` (after Phase 1, probe at 2,827 of 3,000).

| Piece | Today | What that costs |
| --- | --- | --- |
| Function type | `NativeFunction`, a package-private class holding a lambda `(List<Object> args, Map<Object,Object> kwargs) -> Object` | Every function unpacks and casts its own arguments, as in `(String) a.get(0)`. A wrong type surfaces as a Java `ClassCastException`, not a Python `TypeError`. |
| Module registry | A `switch` on the module name in `PyRuntime` (`sys`, `jvm_io`, `ast`), mirrored by `NATIVE_MODULES` in `compiler.py` | Adding a module means editing the runtime and the compiler. Third parties cannot. |
| Native classes | Runtime types such as `PyBytes` each need a `*Methods` class and a line in `TypeSlots` | No way to define a Python-visible class from outside the runtime. |
| Errors | `throw new PyException("OSError", ...)` by string name | No mapping from Java exceptions, no checked signatures. |
| Signatures and docs | None: no `__text_signature__`, `__doc__` or keyword names | `help()` and `inspect.signature` have nothing to show. |
| Machine code | None. No JNI or FFM use anywhere in the tree | Tier D of Phase 4 (posix, mmap, termios) has no foundation yet. |

The roadmap touches extensions in three places, and this plan connects them:

- **"Native-extension strategy is undefined"** is listed as a structural gap. This plan is that strategy.
- **Phase 4** replaces every CPython C module with Java, one worker per module. That work is internal to the runtime, not extension writing, and this plan leaves it as it is.
- **"C extensions (later track)"** defers third-party C wheels until after Phase 5, through HPy or a C-API emulation over FFM. This plan brings it forward: track R2 builds the FFM bridge and track C builds the emulation layer.

The Kotlin-style interop section (I-01 to I-06) is why Java needs no extension API. Interop lets Python import and call any Java class, so a JVM library is used directly. Extensions exist for the other case: code that is already Rust, or that needs what the JVM does not give (SIMD, an OS interface, an existing native library). The two share the value-mapping table and should share its implementation.

## What PyO3-style means on the JVM

PyO3 is easy because the author declares intent on normal typed code and a macro writes the glue. For new code Rust is the extension language, so pyJVM can keep PyO3's own vocabulary almost unchanged; what differs is what sits underneath. Existing C-API extensions keep their source as it is and go through the shim described further down.

| PyO3 on CPython | `pyjvm` crate on pyJVM | Why it differs |
| --- | --- | --- |
| `#[pymodule]`, `#[pyfunction]`, `#[pyclass]`, `#[pymethods]`, `#[new]`, `#[getter]`, `#[setter]`, `#[staticmethod]`, `#[classmethod]` | Same names | Nothing to change |
| `#[pyo3(signature = (...))]`, `#[pyo3(name = "...")]` | `#[pyjvm(signature = (...))]`, `#[pyjvm(name = "...")]` | Attribute namespace follows the crate name |
| `FromPyObject`, `IntoPyObject` | `FromPy`, `IntoPy` | Scalars, strings and byte slices are converted on the JVM side before the call, so most functions never touch these traits |
| `PyResult<T>`, `PyErr`, `PyValueError::new_err`, `create_exception!` | Same names | Nothing to change |
| `Python<'py>` GIL token | `Py<'ctx>` context token | There is no GIL. The token scopes handles to one call |
| `py.allow_threads(...)` | Not needed | Native code never holds a lock the runtime needs |
| `Bound<'py, T>` | `Bound<'ctx, T>` | A call-scoped handle, not a pointer |
| `Py<T>` | `Py<T>` | A global handle that outlives the call |
| Reference counts (`Py_INCREF`) | None | The JVM garbage collector owns Python objects |
| maturin, wheels | `pyjvm-ext`, jars | The JVM's unit of distribution is a jar |

**Goals**

- A first extension in under 10 lines of Rust and one build command.
- Porting a PyO3 crate is mostly mechanical: change the dependency and the `use` line, rename `pyo3` attributes, fix what the compiler flags.
- Python semantics by default: keyword arguments, defaults, `TypeError` with CPython's message wording, `__doc__`, `__text_signature__`, `__module__`.
- Cheap calls for simple signatures. A function over scalars, strings or byte slices is one foreign downcall with no callbacks.
- No generated Java. The jar holds a descriptor and shared libraries; the runtime builds call handles from the descriptor at import.
- Existing extensions run without source changes wherever they stick to CPython's documented C API.

**Non-goals**

- A public Java or Kotlin extension API or annotation processor. Java libraries are reached through interop.
- Loading version-specific CPython binaries (`cp315-cp315` wheels) without a rebuild.
- Emulating CPython internals that the C API leaks only by accident: exact reference counts, `sys.getrefcount`, object addresses that never change meaning, `_Py` private functions.
- Building the native crate against `pyo3` itself. Names and shape match; the crate is `pyjvm`. A PyO3 crate that should run unchanged uses the shim instead.
- JNI. FFM replaces it.

**Design principles**

1. **Declare, do not parse.** Authors never see an argument list or a kwargs map.
2. **Fail at build time.** An unconvertible parameter type, a duplicate name or a bad default is a macro error with the source span, not an import-time surprise.
3. **Insulate from runtime internals.** Extensions see handles and the ABI table only. Phase 2 can change `str`, `dict` and threading without breaking a built extension.
4. **Narrowing is always checked.** Python `int` to `i32` raises `OverflowError` on loss, as the interop table already specifies.

## Author experience

An extension is a normal Cargo library crate with one dependency and a handful of attributes. These sketches are the target API, not code that exists yet.

**Functions and a module**

```rust
use pyjvm::prelude::*;

/// Greatest common divisor of two integers.
#[pyfunction]
fn gcd(a: i64, b: i64) -> i64 {
    if b == 0 { a.abs() } else { gcd(b, a % b) }
}

#[pyfunction]
#[pyjvm(signature = (xs, *, strict = false))]
fn mean(xs: Vec<f64>, strict: bool) -> PyResult<f64> {
    if xs.is_empty() {
        return if strict { Err(PyValueError::new_err("mean of empty sequence")) } else { Ok(0.0) };
    }
    Ok(xs.iter().sum::<f64>() / xs.len() as f64)
}

/// CRC-32 of any bytes-like object. Runs on the direct path: no handles, no callbacks.
#[pyfunction]
fn crc32(data: &[u8]) -> u32 {
    crc32fast::hash(data)
}

#[pymodule]
fn fastmath(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(gcd, m)?)?;
    m.add_function(wrap_pyfunction!(mean, m)?)?;
    m.add_function(wrap_pyfunction!(crc32, m)?)?;
    m.add_class::<Counter>()
}
```

**A class**

```rust
#[pyclass]
struct Counter { n: i64 }

#[pymethods]
impl Counter {
    #[new]
    #[pyjvm(signature = (start = 0))]
    fn new(start: i64) -> Self { Counter { n: start } }

    #[pyjvm(signature = (by = 1))]
    fn incr(&mut self, by: i64) -> i64 { self.n += by; self.n }

    #[getter]
    fn value(&self) -> i64 { self.n }

    fn __repr__(&self) -> String { format!("Counter({})", self.n) }
}
```

**From Python**

```python
import fastmath

fastmath.gcd(12, 18)                 # 6
fastmath.mean([], strict=True)       # ValueError: mean of empty sequence
fastmath.gcd("a", 1)                 # TypeError: 'str' object cannot be interpreted as an integer
fastmath.gcd(2**70, 1)               # OverflowError: Python int too large to convert to i64
fastmath.crc32(b"hello")             # 907060870
c = fastmath.Counter(); c.incr(); c  # Counter(1)
help(fastmath.gcd)                   # gcd(a, b) ... Greatest common divisor of two integers.
```

**Project layout and build**

```toml
# Cargo.toml
[lib]
crate-type = ["cdylib"]

[dependencies]
pyjvm = "0.1"
```

```bash
pyjvm-ext new fastmath                 # Cargo project, sample test, CI file
pyjvm-ext develop                      # cargo build, register the jar on the dev path
pyjvm-ext test                         # compile tests/*.py with pyJVM, run on the JVM
pyjvm-ext build --release              # fastmath-0.1.0.jar

pyjvm315 app.py -o build/jvm -c App --ext-path fastmath-0.1.0.jar
java --enable-native-access=ALL-UNNAMED -cp build/jvm:fastmath-0.1.0.jar App
```

**Porting from PyO3.** For the code above, the whole diff from a PyO3 crate is three kinds of line:

```diff
-pyo3 = { version = "0.2x", features = ["extension-module"] }
+pyjvm = "0.1"

-use pyo3::prelude::*;
+use pyjvm::prelude::*;

-#[pyo3(signature = (xs, *, strict = false))]
+#[pyjvm(signature = (xs, *, strict = false))]
```

Code that uses the GIL token (`Python<'py>`, `py.allow_threads`, `Python::with_gil`) or calls `pyo3::ffi` directly needs real changes. The porting guide (ticket R3-03) lists each case.

**Or do not port at all.** A PyO3 crate built with the `abi3` feature produces a stable-ABI library, and that binary runs through the shim unchanged. Port to `pyjvm` only when the boundary cost matters.

**C, and anything that already speaks the C API.** There is no pyJVM-specific C dialect. C code is ordinary CPython extension code:

```c
#define Py_LIMITED_API 0x030F0000
#include <Python.h>

static PyObject *gcd(PyObject *self, PyObject *args) {
    long long a, b;
    if (!PyArg_ParseTuple(args, "LL", &a, &b)) return NULL;
    while (b) { long long t = a % b; a = b; b = t; }
    return PyLong_FromLongLong(a < 0 ? -a : a);
}

static PyMethodDef methods[] = {
    {"gcd", gcd, METH_VARARGS, "Greatest common divisor of two integers."},
    {NULL, NULL, 0, NULL}
};

static struct PyModuleDef module = {PyModuleDef_HEAD_INIT, "cmath_demo", NULL, -1, methods};

PyMODINIT_FUNC PyInit_cmath_demo(void) { return PyModule_Create(&module); }
```

```bash
# An existing stable-ABI wheel, as downloaded: wrap it in a jar the importer finds
pyjvm-ext repack somepkg-1.4-cp39-abi3-manylinux_2_28_x86_64.whl

# A C or Cython project from source, compiled against pyJVM's Python.h
pyjvm-ext wheel ./cmath_demo            # cmath_demo-0.1.0.jar
```

The same file compiles for CPython, which is what makes CPython itself the test oracle for this route.

## Architecture

A Rust extension reaches the runtime through a small handle-based ABI carried over the Foreign Function and Memory API (FFM), with no JNI and no generated Java. At build time it reduces to a descriptor plus shared libraries in a jar.

&#91;embedded content: extension architecture · build time to import\]

The descriptor is the contract: the compiler and the importer read it without loading native code, and no library is opened until an extension is imported.

### Java 25 baseline

Java 25 is the minimum for the whole project. FFM has been a final API since Java 22, so on a 25 baseline the bridge is ordinary code in the core runtime jar: no preview flags, no separate native jar, no version gate at import. Three other things come with it: the critical linker option used for zero-copy byte slices, `ScopedValue` (final in 25) for carrying the per-call handle table without a thread-local lookup, and one long-term-support release to test against.

What has to change is small but touches everything, so it is its own lead-run ticket (R0-01): `DEFAULT_TARGET = 21` in `classfile.py` and the checks that reject other targets, the runtime's `javac --release 21`, CI, and the "Java 21 only" wording in `README.md` and `JVM_BACKEND.md`. Generated class files move from major version 65 to 69. Running needs `--enable-native-access=ALL-UNNAMED`, or the `Enable-Native-Access` manifest attribute in an application jar; without it Java 25 prints a warning for each native-access call site.

### Crates

| Crate | Contents |
| --- | --- |
| `pyjvm-sys` | The raw ABI: `PjApi`, `PjHandle`, `PjModuleDef` as `#[repr(C)]` types. The ABI's only definition; no C header is shipped |
| `pyjvm-macros` | `#[pymodule]`, `#[pyfunction]`, `#[pyclass]`, `#[pymethods]`, `create_exception!` |
| `pyjvm` | Safe API: `Bound<'ctx, T>`, `Py<T>`, `PyResult`, `PyErr`, `FromPy` and `IntoPy`, typed wrappers (`PyList`, `PyDict`, `PyBytes`, `PyModule`) |

### The ABI

- **One exported symbol per module**: `pyjvm_ext_init_<module>(const PjApi*)` returns a static `PjModuleDef`: name, docstring, ABI version, and tables of function and class definitions, each with a signature string.
- **`PjApi`** is a versioned struct of function pointers into the runtime (FFM upcall stubs): create and convert values, get and set attributes, call, raise, and manage handles. New entries are appended; existing ones never change meaning.
- **Handles, not pointers.** A Python object appears in Rust as an opaque 64-bit `PjHandle`. Handles created during a call live in a per-call table and are released when the call returns, the model HPy and JNI local references use. A `Py<T>` promotes one to a global handle. The garbage collector stays free to move objects, and no reference counting is exposed.
- **Errors.** Functions return a status; the error itself is set through the API table and held per thread, then raised as a Python exception when control returns to the JVM.

### Two call paths

| Path | Used when | How it runs | Cost |
| --- | --- | --- | --- |
| Direct | Every parameter and the result are scalars, `&str`, `String`, `&[u8]` or `Vec` of scalars | The JVM converts and checks arguments, then makes one FFM downcall straight to the user's function. No handles, no upcalls | One downcall the JIT can inline; strings and bytes are copied into a call arena |
| Handle | Any parameter is a Python object, `PyList`, `PyDict`, a `#[pyclass]` or a generic container | Arguments are passed as handles; Rust calls back through `PjApi` | One downcall plus an upcall per runtime operation |

The macros pick the path from the Rust signature; authors do not choose. `#[pyjvm(critical)]` on a direct-path function additionally lets `&[u8]` point at the Java array without a copy (FFM's critical linker option). It must only be used on short, non-blocking functions, because a critical call holds off garbage collection.

### Type conversions

Conversion and its error messages live on the JVM side, in one table shared with Kotlin-style interop. They are also the single place that changes when Phase 2 swaps `str` and `dict` representations.

| Rust parameter type | Accepts from Python | On mismatch | Path |
| --- | --- | --- | --- |
| `i8` to `i64`, `u8` to `u64`, `isize`, `usize` | `int`, or any object with `__index__` | `TypeError`; `OverflowError` on range loss | Direct |
| `f64`, `f32` | `float`, `int`, or `__float__` | `TypeError` | Direct |
| `bool` | `bool` only (as PyO3) | `TypeError` | Direct |
| `&str`, `String` | `str` | `TypeError`; lone surrogates raise `UnicodeEncodeError` | Direct, copied as UTF-8 |
| `&[u8]`, `Vec<u8>` | Any buffer-protocol object | `TypeError` | Direct, copied unless critical |
| `Vec<i64>`, `Vec<f64>` and other scalar vectors | Any sequence of matching items | `TypeError` naming the index | Direct, one packed copy |
| `Option<T>` | `None` or `T` | As for `T` | As for `T` |
| `num_bigint::BigInt` (feature flag) | `int` of any size | `TypeError` | Handle |
| `Vec<T>`, `HashMap<K, V>`, `HashSet<T>`, tuples | `list`, `dict`, `set`, `tuple` and their ABCs | `TypeError` | Handle, copied |
| `Bound<'_, PyAny>`, `Bound<'_, PyList>` and other wrappers | The matching Python type | `TypeError` | Handle, no copy |
| `PyRef<T>`, `PyRefMut<T>`, `&T`, `&mut T` of a `#[pyclass]` | Instances of that class | `TypeError`; `RuntimeError` if already mutably borrowed | Handle |

### Classes

A `#[pyclass]` struct lives on the Rust heap. The JVM holds it through one internal Java proxy class carrying the pointer and a per-class method table, freed by `java.lang.ref.Cleaner`, the mechanism roadmap P2-08 already plans for `__del__`. Dunder methods in `#[pymethods]` (`__len__`, `__getitem__`, `__iter__`, `__add__`, `__eq__`, `__hash__`, `__enter__`) map to the runtime's slot table through a registration call that replaces the hard-coded `static` block in `TypeSlots`. `&mut self` methods use an atomic borrow flag, as in PyO3, and classes must be `Send + Sync` because the runtime is free-threaded. Subclassing from Python is opt-in with `#[pyclass(subclass)]` and depends on roadmap P2-04.

### Errors

- `PyErr` and the `Py*Error::new_err` constructors build real Python exceptions; `create_exception!` defines new types in the module.
- `From` conversions cover the standard library as PyO3 does: `std::io::Error` to `OSError` and its subclasses by error kind, `ParseIntError` and `ParseFloatError` to `ValueError`, `Utf8Error` to `UnicodeDecodeError`. `?` works in any function returning `PyResult`.
- A panic is caught at the boundary and raised as `PanicException`, which derives from `BaseException` so that `except Exception` does not hide it.

### Discovery and loading

`pyjvm-ext build` loads the library once and writes `META-INF/pyjvm/extensions/<module>.json` into the jar: module name, ABI version, library paths per platform, and each function's and class's signature and docstring. Shared libraries sit under `META-INF/pyjvm/native/<os>-<arch>/`.

- **Now (before roadmap P3-01).** `PyRuntime`'s name `switch` becomes an internal `ExtensionRegistry` lookup over descriptors on the classpath. The compiler's hard-coded `NATIVE_MODULES` set gains the modules found in jars passed with `--ext-path`. The existing Java modules (`sys`, `jvm_io`, `ast`) register through the same registry but keep their Java implementation.
- **On first import.** The loader extracts the matching library to a content-addressed cache directory, opens it with `SymbolLookup.libraryLookup`, calls the init symbol and builds one downcall handle per function.
- **After P3-01.** An `ExtensionFinder` sits on `sys.meta_path` beside the `JarModuleFinder`, returning a real `ModuleSpec`. Loading is two-phase like PEP 489, with per-module state, so a module can be loaded twice.
- **Static binding.** When the compiler can prove argument types for a direct-path function, it links the call site to the downcall handle and skips boxing (ticket R1-06).
- **Stubs.** The same descriptor generates `.pyi` files, sharing the generator planned in I-04.

First-tier targets: `linux-x86_64`, `linux-aarch64`, `macos-aarch64`, `windows-x86_64`.

### Threads and versioning

pyJVM has no GIL to hold or release. Extension functions may be called from several threads at once, as in CPython's free-threaded build, and a thread inside a normal downcall does not block garbage collection. Each library carries an ABI version; the runtime loads the same major version and refuses others with an `ImportError` that names both.

## The CPython C-API shim

`libpyjvm-capi` is a Rust library that exports CPython's C symbols and implements them over the handle ABI, so an extension that believes it is talking to CPython is talking to pyJVM. PyPy (`cpyext`) and GraalPy run C extensions the same way.

&#91;embedded content: two extension routes · one ABI\]

The shim is a client of the same ABI the `pyjvm` crate uses, which is why the Rust route comes first and why it stays the cheaper one.

### What ships

- **`libpyjvm-capi`** for each first-tier platform, loaded the first time a C-API extension is imported.
- **Headers.** CPython 3.15's `Include/` tree with pyJVM's `pyconfig.h` and a small patch set that turns struct-reading macros into function calls. They stay under the PSF licence.
- **Build metadata.** `sysconfig` values (include directory, extension suffix `.pyjvm-315-<platform>.so`) so that setuptools, meson-python, Cython and maturin find the headers and name their output correctly.

### Objects and reference counts

- **Stubs.** A Python object that crosses into C gets a small native struct holding `ob_refcnt`, `ob_type` and a handle, at the offsets the stable ABI fixes. Reference-count macros that C code inlines therefore work on it.
- **Identity.** One stub per object, cached, so pointer equality in C matches `is` in Python.
- **Lifetime.** While C holds references, the stub keeps the object alive through a global handle. When C's count returns to the baseline the handle turns weak and the JVM collector decides. This is the scheme `cpyext` and GraalPy use.
- **Objects allocated in C.** Instances of types defined by an extension live in native memory. The JVM sees them through the same proxy class as a Rust `#[pyclass]`, and `tp_dealloc` runs when both sides have let go.
- **Borrowed references.** `PyList_GetItem`, `PyTuple_GetItem` and `PyDict_GetItem` return pointers the caller does not own. Their stubs are kept alive by the container's stub.
- **Cycles.** A reference cycle that passes through a C-allocated object is not collected in the first version. Honouring `tp_traverse` is ticket C-10.

### Three levels of compatibility

| Level | What the shim provides | What runs |
| --- | --- | --- |
| L1: stable ABI | Every function in the limited API; structs stay opaque | Unmodified `abi3` binaries: PyO3 with the `abi3` feature, Cython in limited-API mode, hand-written C |
| L2: full API, rebuilt | pyJVM's headers, where `Py_TYPE`, `PyTuple_GET_ITEM`, `PyList_GET_SIZE` and similar are calls; static `PyTypeObject` with `PyType_Ready` | Most Cython output and older C extensions, recompiled |
| L3: layout-dependent | Native mirrors of tuple, list, bytes and string storage that C code reads directly | Extensions in the NumPy class. Not committed; see open decisions |

### Threads and the GIL

C extensions assume a global interpreter lock; pyJVM has none. The shim keeps one C-extension lock, taken on every entry into a shim-loaded extension, released by `Py_BEGIN_ALLOW_THREADS` and acquired by `PyGILState_Ensure`. A module that declares `Py_mod_gil` as not used skips it. CPython's free-threaded build treats undeclared modules the same way. Rust extensions on the `pyjvm` crate never take this lock.

### Loading

- The shim, not the JVM, opens the extension library, so the `Py*` symbols an unmodified `abi3` binary expects resolve to the shim. On Windows, where such binaries import from `python3.dll`, the shim ships a library by that name.
- `PyInit_<name>` is called with single-phase or multi-phase (PEP 489) initialisation.
- The jar descriptor for a C-API module holds only its name and libraries. Functions are discovered at init, so these modules get no static binding and no generated stubs.

### Cost

Every C-API call that needs the runtime is an upcall into the JVM, and every object that crosses needs a stub. The shim therefore answers natively whatever it can: reference counts, type checks, cached small integers and strings, argument-tuple parsing. Expect a shim extension to be clearly slower at the boundary than the same code on CPython, and slower than a `pyjvm` port. Ticket C-09 measures it before any claim is made.

### Conformance from CPython's own sources

- CPython's `Misc/stable_abi.toml` lists every stable-ABI symbol. A generated checklist marks each one exported or missing with a reason, and CI ratchets it the way the probe baseline does.
- CPython's `_testlimitedcapi` and `_testcapi` modules are built against the shim and driven by `Lib/test/test_capi`. The roadmap lists both under Tier E as unsupported; this moves them out, and some of the 173 probe bodies still unsupported depend on `_testcapi`.

### `ctypes` and `cffi`

The roadmap tags `ctypes` unsupported "until FFM bridge exists". The loader and downcall builder from R2 are that bridge (ticket R4-01). `cffi` in API mode generates a C-API extension and runs on the shim; its ABI mode needs the `ctypes`-style calls.

## Build tooling and packaging

`pyjvm-ext` does for pyJVM what maturin does for PyO3: one tool from an empty directory to an importable artifact. It wraps `cargo` and ships as a new console script in the existing `pyjvm315` Python package, next to `pyjvm315` and `pyjvm315-conformance`.

| Command | Does |
| --- | --- |
| `pyjvm-ext new <name>` | Scaffolds a Cargo library crate, `pyjvm-ext.toml`, a sample test and a CI file |
| `pyjvm-ext develop` | `cargo build` in debug mode for the host platform, then registers the jar on the development extension path |
| `pyjvm-ext test` | Compiles `tests/*.py` with pyJVM and runs them on the JVM against the built extension |
| `pyjvm-ext build --release [--target ...]` | Builds each target, extracts the descriptor, and assembles `<name>-<version>.jar` with libraries, descriptor and `.pyi` stubs |
| `pyjvm-ext repack <wheel>` | Wraps an existing `abi3` wheel, unchanged, in a jar with a C-API descriptor. Refuses version-specific wheels and says why |
| `pyjvm-ext wheel <project>` | Builds a C, Cython or PyO3 project from source against pyJVM's headers, using the project's own build backend with pyJVM's `sysconfig` values, then packs the result as a jar |
| `pyjvm-ext stubs` | Writes `.pyi` files from a descriptor |
| `pyjvm-ext check <jar>` | Validates a jar: descriptor schema, ABI version, exported symbols, native targets present, and for C-API modules any `Py*` symbol the shim does not yet export |

**Cross-compilation.** Linux and Windows targets build from one Linux host through `cargo-zigbuild`; macOS targets build on a macOS runner. The scaffolded CI file runs both and merges the libraries into one jar.

**Mixed projects.** A `python/` directory beside `src/` is compiled by pyJVM and packed into the same jar. This is the usual PyO3 layout: a Python package that wraps a private native module such as `_fastmath`.

**The artifact is a jar.** Jars are what the JVM, Gradle, Maven and the roadmap's `JarModuleFinder` already understand, and Maven coordinates give versioning and distribution for free. A wheel tag for pyJVM so that `pip install` can deliver the same jar is a later item, after roadmap Phase 3 decides how pure-Python wheels are installed.

**Building from source runs on host CPython at first.** `pyjvm-ext wheel` drives setuptools, meson-python or maturin under an ordinary CPython with pyJVM's build metadata substituted, the way cross-compilation tools do. Running those build backends on pyJVM itself needs `pip` and much of the stdlib, so it waits for roadmap Phase 5.

**Gradle and Maven.** The joint-compilation plugin planned in I-04 gains one feature: extension jars on the classpath are passed to the pyJVM compiler as its extension path, and the run configuration gets `--enable-native-access`.

## Phased delivery

The Java 25 move and the Rust route can start now. The shim starts once the handle ABI exists (R2-02) and reaches unmodified wheels only after roadmap Phase 2 delivers strings, subclassing, buffers and threads. Nothing in the roadmap waits on this plan.

&#91;embedded content: extension tracks against the main roadmap\]

The highlighted bars are milestones M0 to M2. The shim's first slice (C-01 to C-03) follows the ABI inside the same period; everything to its right waits on named roadmap tickets.

| Milestone | Tickets | Exit criterion |
| --- | --- | --- |
| M0: Java 25 | R0-01 | Every existing gate is green on JDK 25 with class files at major version 69 |
| M1: first Rust module | R1-01, R1-02, R2-01 to R2-04, R3-01 | The `fastmath` functions, built outside the pyJVM tree with `pyjvm-ext build`, are imported by a compiled Python program and pass their twin cases on Linux |
| M2: PyO3 parity on the native crate | R1-03, R1-04, R2-05 to R2-07, R3-02, R3-03 | One small published PyO3 crate, chosen by the lead, is ported with only dependency, `use` and attribute-name changes, passes its own Python tests, and ships as one jar for four platforms |
| M3: first C extension | C-01 to C-03 | The C sample in this plan, compiled against pyJVM's headers, passes the same test file as on CPython; the matching `_testlimitedcapi` groups pass |
| M4: unmodified stable-ABI wheels | C-04 to C-06, C-08, R3-05 | One PyO3 `abi3` wheel and one C `abi3` wheel, chosen by the lead and downloaded as published, pass their own tests after `pyjvm-ext repack` |
| M5: C and Cython from source | C-07, C-09, C-10, R3-06 | One Cython project, chosen by the lead, builds with `pyjvm-ext wheel` and passes its tests; boundary costs are published |
| M6: integrated with the roadmap | R1-05, R1-06, R2-08, R3-04 | Extensions load through `importlib`, bind statically where types are proven, and build inside the mixed Gradle sample |

Tickets follow the roadmap's format and worker rules: one ticket per Sonnet session, differential cases with every behaviour change, no placeholders, every Java method under 8,000 bytes. A C-API function that is not implemented is absent from the export list; it is never a stub that returns `NULL`.

| ID | Ticket | Depends on | Acceptance |
| --- | --- | --- | --- |
| R0-01 | Re-target to Java 25: compiler default and target checks, runtime `javac --release 25`, CI images, README and `JVM_BACKEND.md`. Lead-run | none | All gates green on JDK 25 under `-Xverify:all`; bootstrap byte-identical; benchmarks re-baselined once, with the before and after recorded |
| R1-01 | Internal array-based calling convention, `ExtensionRegistry` in place of the name `switch`, compiler `--ext-path`. Lead-run: it edits `PyRuntime` and `compiler.py` | R0-01 | Existing Java modules load through the registry; all gates green; benchmarks within 1.05 |
| R1-02 | Descriptor format with a JSON schema; JVM-side converter table with CPython-worded errors | R1-01 | One differential case per conversion-table row, including each failure mode |
| R1-03 | Native class support: proxy class, slot registration call, `Cleaner`-backed release | R1-01 | A test class registered from Java test code answers `len`, indexing, iteration and `repr` |
| R1-04 | `__doc__`, `__text_signature__`, `__module__`; `.pyi` generation from descriptors | R1-02 | Attributes match the twin module; stubs pass Pyright |
| R1-05 | `ExtensionFinder` and loader on `sys.meta_path`, for jars and for extension files on `sys.path`; per-module state | P3-01, R1-02 | `importlib.import_module` and `importlib.reload` work on an extension; `__spec__` is populated |
| R1-06 | Compiler links proven-type call sites straight to the downcall handle | I-02, R2-03 | No boxing in generated bytecode; call benchmark within 1.5x of a Java static call |
| R2-01 | FFM bridge in the core runtime: library loader, extraction cache, native-access configuration; CI adds Rust | R0-01, R1-01 | A test library loads and is called on all four first-tier platforms |
| R2-02 | ABI v0 in `pyjvm-sys`: `PjApi` upcall table, per-call handle table, per-thread error state | R2-01 | A fixture written against `pyjvm-sys` alone round-trips every API entry; no handle survives its call in a leak test |
| R2-03 | Direct call path: signature strings to downcall handles with JVM-side conversion | R2-02, R1-02 | Scalar functions make zero upcalls, verified by a counter in the test build |
| R2-04 | `pyjvm` and `pyjvm-macros`: `#[pymodule]`, `#[pyfunction]`, signatures, `PyResult`, `PyErr`, `create_exception!` | R2-03 | The function sample in this plan compiles unchanged and passes |
| R2-05 | `#[pyclass]`, `#[pymethods]`, borrow flag, dunder methods | R2-04, R1-03 | `Counter` passes its twin cases; a drop counter shows no leak after `gc.collect()` |
| R2-06 | Typed wrappers and container conversions: `PyList`, `PyDict`, `PyBytes`, `Vec`, `HashMap`, tuples, batch API entries | R2-04 | Building a 10,000-item list makes one upcall, not 10,000 |
| R2-07 | Panic safety, thread stress, handle-table exhaustion | R2-04 | A panicking function raises `PanicException`; 8 threads for 60 s with no crash or leak |
| R2-08 | Zero-copy buffers and `#[pyjvm(critical)]` | P2-06, R2-04 | A byte-sum kernel over `bytearray` shows no copy in an allocation profile |
| C-01 | Shim skeleton: `pyjvm-capi` crate, imported CPython headers and patch set, `PyObject` stubs and the reference-count scheme, `PyInit_` loading, `PyMethodDef` calling conventions, C-extension lock | R2-02, R2-07 | A module with one `METH_NOARGS` function loads and is called; stub and handle counts return to zero in a leak test |
| C-02 | Concrete objects: `int`, `float`, `bool`, `None`, `str` through its UTF-8 API, `bytes`, `tuple`, `list`, `dict`, `set`; `PyArg_Parse*`, `Py_BuildValue`; `PyErr_*` and exception objects | C-01 | The C sample passes; matching `_testlimitedcapi` groups pass |
| C-03 | Abstract protocols: `PyObject_*`, call and vectorcall, `PyNumber_*`, `PySequence_*`, `PyMapping_*`, `PyIter_*`, `PyImport_*`; multi-phase init and module state | C-02 | Matching `_testlimitedcapi` groups pass |
| C-04 | Types defined in C: `PyType_FromSpec`, slots mapped to the runtime slot table, members and getsets, `PyCapsule`, allocation and `tp_dealloc` | C-03, R1-03, P2-04 | A C-defined class passes the `Counter` twin cases and can be subclassed from Python |
| C-05 | Stable-ABI completion: checklist generated from `stable_abi.toml` with a CI ratchet; unmodified binary loading on Linux, macOS and Windows | C-04, P2-03, P2-09 | Checklist at the coverage the lead sets, every gap carrying a reason; milestone M4's two wheels pass |
| C-06 | Buffer protocol and `memoryview` in the C API | C-04, P2-06 | A C extension reads a `bytearray` and exports its own buffer to `memoryview` |
| C-07 | Full API when rebuilt: macro-to-function header patches, static types with `PyType_Ready`, `_testcapi` builds | C-05 | Milestone M5's Cython project passes; agreed `test_capi` subset passes |
| C-08 | Thread-state and GIL functions in full; `Py_mod_gil` honoured | C-01, P2-10 | A C extension that releases the lock around a blocking call lets other Python threads run; 8-thread stress without the declaration shows no race |
| C-09 | Boundary benchmarks: the same functions as shim C, `pyjvm` Rust, and C on CPython | C-02, R2-04 | Results published in `benchmarks/README.md` and tracked by the gate |
| C-10 | Cycle collection through `tp_traverse` and `tp_clear` | C-04, P2-08 | A cycle through a C-allocated object is freed after `gc.collect()` |
| C-11 | Spike: level L3 and NumPy | C-07 | A written go or no-go listing every missing function and layout dependency found by attempting the build |
| R3-01 | `pyjvm-ext new`, `develop`, `build`, `test` for the host platform | R2-04 | Milestone M1 scripted end to end in CI |
| R3-02 | Cross targets and the scaffolded CI file | R3-01 | One jar carries four targets and loads on each |
| R3-03 | `pyjvm-ext check`; "First extension", "Porting from PyO3" and "Running an existing C extension" guides | R3-01, R2-05 | A new contributor follows the guide to a passing test without help |
| R3-04 | Extension path and native-access flag in the Gradle and Maven plugins | I-04, R3-01 | The mixed Java, Kotlin and Python sample imports an extension jar |
| R3-05 | `pyjvm-ext repack` for `abi3` wheels | C-05, R3-01 | Milestone M4 scripted end to end in CI |
| R3-06 | `pyjvm-ext wheel`: build from source on host CPython with pyJVM's `sysconfig` values | C-07, R3-01 | Milestone M5 scripted end to end in CI |
| R4-01 | `ctypes` over the R2 loader | R2-01, P3-05 | `test_ctypes` core cases pass; the Tier E tag is removed |

**Parallelism.** R0-01 and R1-01 are serial and lead-run because they touch the most contended files. After them, R1-02 and R2-01 start together. The Rust crates and the shim live in a new `rust/` directory, so R2 and C workers never collide with Phase 2 workers in `runtime/`. From C-02 on, the shim splits by API family (numbers, strings, containers, protocols) and takes several workers at once.

**Worker environment.** Workers need JDK 25, a Rust toolchain, a C compiler and a CPython 3.15 source checkout (for the headers, `stable_abi.toml` and the `_testcapi` sources). The lead should add these to the worker brief and to CI before handing out R2-01.

**Changes to the roadmap.** No ticket there gains a dependency. Four edits: replace "Java 21" with "Java 25" throughout, including Tier D's FFM note; replace the "Native-extension strategy is undefined" gap with a pointer to this plan; move `_testcapi` and `_testinternalcapi` apart in Tier E, marking the first "covered by track C" while the internal one stays unsupported; and replace the "C extensions (later track)" paragraph, which this plan supersedes.

## Testing and validation

Each route has its own oracle. For the Rust crate it is a pure-Python twin module run on CPython by the existing differential runner. For the shim it is CPython itself: the same C source or the same binary wheel, with the same Python test file, must print the same output on both.

- **Twin cases** in `tests/conformance/ext_*`: argument binding (positional, keyword, defaults, keyword-only, `*args`, `**kwargs`), every row of the conversion table, every failure message, class protocols.
- **PyO3 cross-check**: the `fastmath` sample is also kept as a real PyO3 crate. It runs three ways against one test file: on CPython, on pyJVM through the shim as an `abi3` build, and on pyJVM as the `pyjvm` port.
- **Stable-ABI checklist**: generated from CPython's `stable_abi.toml`. Each symbol is exported or carries a reason; the exported count may only rise.
- **CPython's C-API tests**: `_testlimitedcapi` and `_testcapi` built against the shim and driven by `Lib/test/test_capi`, with a tag file for expected failures in the format roadmap Phase 5 defines.
- **Reference-count hygiene**: a debug build of the shim counts live stubs and global handles; every test ends at zero or names its leak.
- **Macro tests**: `trybuild` compile-fail fixtures asserting the error text and span for each rejected declaration.
- **Descriptor schema**: a JSON schema checked in; `pyjvm-ext check` and CI validate every built jar against it.
- **ABI and stress tests**: handle-leak counter, upcall counter, panic and thread-stress cases named in the tickets, with the Rust side under AddressSanitizer on Linux.
- **Existing gates stay in force**: 3,000-body probe floor, strict execution lane, bootstrap byte-identity, method-size lint, benchmark gate. R0-01 and R1-01 are the only tickets that can move them.
- **Benchmark kernels** in `benchmarks/`: call overhead on the direct path, the handle path and the shim, against a Java static call, a pure-Python function and the same C on CPython. The first run sets the baseline; no figure is claimed before then, except R1-06's 1.5x.
- **Licence check**: `tests/test_licenses.py` learns the `rust/` tree, and the imported CPython headers are added to `THIRD_PARTY_LICENSES`.

## Risks and open questions

**Decided in this revision**

- Java 25 is the minimum for the compiler's output, the runtime and extensions.
- Existing C-API extensions are supported through a shim, alongside the native Rust crate.

**Decisions for you**

1. **A lock for C extensions.** Existing extensions assume the GIL. Options: one global C-extension lock for modules that do not declare themselves thread-safe, or no lock and a documented "at your own risk". Recommended: the lock, as CPython's free-threaded build does. It costs parallelism only inside undeclared C extensions.
2. **How far the shim goes.** Options: stable ABI only (L1), or also the full API when rebuilt from source (L2). L1 alone covers PyO3 and newer Cython projects; most older C extensions need L2. Recommended: both, L1 first, because L2 is mostly header work once L1 exists.
3. **NumPy.** It reads object layouts directly and uses several hundred API functions, and it took PyPy and GraalPy years. Recommended: not a committed target; decide after spike C-11 reports.
4. **Licences.** Recommended: the GPL linking exception for the `pyjvm` crates and for `libpyjvm-capi`, so extensions and their users are not forced to be GPL; the imported CPython headers stay under the PSF licence. The exception text is still an unreviewed draft, per the README, and should be reviewed before anything is published to crates.io.

**Open questions**

- Should the shim be optional at install time? It is a native library per platform that pure-Python and Rust-only users never load.
- Should Phase 4 Tier D modules (posix, termios, pwd, resource) be Rust extensions rather than Java calling FFM directly? Rust's `libc` and `rustix` crates already carry the per-platform struct layouts. Suggested: try one module after M2 and compare.
- Should pyJVM also accept HPy extensions? HPy's handle model is close to this ABI, but few projects publish HPy builds. Suggested: no work unless a wanted package ships one.
- Which packages matter to you? Milestones M4 and M5 each name "one wheel chosen by the lead". Naming real targets now would steer which API families C-02 to C-05 do first.

**Risks**

- **Size of the shim.** The stable ABI is several hundred functions and the full API well over a thousand. This track is larger than the rest of the plan together, and comparable projects spent years on it. Mitigation: the checklist ratchet makes progress countable, the three levels give stopping points, and real target packages set the order.
- **Dependence on Phase 2.** Unmodified wheels need code-point strings (P2-03), builtin subclassing (P2-04), buffers (P2-06), exceptions (P2-09) and threads (P2-10). A slip there moves M4. Mitigation: C-01 to C-03 need none of them and prove the design early.
- **Boundary cost.** Shim calls pay stubs, emulated reference counts and JVM upcalls; object-heavy C extensions may run several times slower than on CPython. Mitigation: native fast paths inside the shim, published measurements (C-09), and the `pyjvm` crate as the route for hot code.
- **Memory leaks at the boundary.** Reference-count bugs in extensions that CPython tolerates, and cycles through C objects before C-10, show up as leaks. Mitigation: the debug stub counter and a documented list of known-leaking patterns.
- **Extensions that rely on CPython internals.** Private `_Py` functions, exact reference counts, or direct struct access will fail to link or misbehave. Mitigation: `pyjvm-ext check` reports unresolved symbols before anything runs.
- **Java 25 migration.** Every user and CI job moves JDK at once, and benchmark baselines shift with the JIT. Mitigation: R0-01 is isolated, lead-run and re-baselines once with both numbers recorded.
- **ABI frozen too early.** Once jars are published the handle ABI is a promise, while Phase 2 still changes representations. Mitigation: handles hide representations by design; version it `0.x` until P2-03 and P2-10 land. The shim itself is a second, demanding client that will expose gaps before third parties do.
- **More toolchains.** Rust, a C compiler, cross-compilation and four native targets join CI. Mitigation: all of it lives in `rust/` and its own CI jobs; the Python and Java gates do not depend on it.
- **Contention on shared files.** R0-01 and R1-01 edit the files the roadmap already flags. Mitigation: lead-run, merged in a quiet window, before other tickets start.
