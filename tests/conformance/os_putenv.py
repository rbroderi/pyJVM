import os
import sys


def attempt(label, fn, *args):
    try:
        print(label, "->", repr(fn(*args)))
    except BaseException as e:
        print(label, "!!", type(e).__name__, e.args)


print(callable(os.putenv), callable(os.unsetenv), hasattr(os, "fchmod"))

# os.environ is the Python-side mapping: writes go through putenv/unsetenv, getenv reads the mapping
os.environ["PYJVM_PUTENV_A"] = "one"
print(os.getenv("PYJVM_PUTENV_A"), os.environ["PYJVM_PUTENV_A"], "PYJVM_PUTENV_A" in os.environ)
os.environ["PYJVM_PUTENV_A"] = "two"
print(os.getenv("PYJVM_PUTENV_A"))
del os.environ["PYJVM_PUTENV_A"]
print(os.getenv("PYJVM_PUTENV_A"), os.getenv("PYJVM_PUTENV_A", "dflt"))
os.environ.setdefault("PYJVM_PUTENV_B", "x")
print(os.environ.pop("PYJVM_PUTENV_B"), os.environ.pop("PYJVM_PUTENV_B", None))

# putenv alone does not touch os.environ
os.putenv("PYJVM_PUTENV_RAW", "raw")
print(os.getenv("PYJVM_PUTENV_RAW"))
attempt("unset", os.unsetenv, "PYJVM_PUTENV_RAW")
attempt("unset missing", os.unsetenv, "PYJVM_PUTENV_NEVER_SET")
attempt("empty value", os.putenv, "PYJVM_PUTENV_E", "")
attempt("non-ascii", os.putenv, "PYJVM_PUTENV_U", "héllo")

# argument validation
attempt("equals", os.putenv, "A=B", "x")
attempt("equals unset", os.unsetenv, "A=B")
attempt("null name", os.putenv, "A\0B", "x")
attempt("null value", os.putenv, "A", "x\0y")
attempt("null unset", os.unsetenv, "A\0B")
attempt("int name", os.putenv, 1, "x")
attempt("int value", os.putenv, "A", 1)
attempt("int unset", os.unsetenv, 1)
attempt("one arg", os.putenv, "A")
attempt("two unset", os.unsetenv, "A", "B")
if os.name == "nt":
    attempt("empty name", os.putenv, "", "x")
    attempt("empty unset", os.unsetenv, "")
    attempt("leading equals", os.putenv, "=A", "x")
    attempt("bytes name", os.putenv, b"A", "v")
    attempt("bytes unset", os.unsetenv, b"A")
    attempt("limit ok", os.putenv, "B", "v" * 32765)
    attempt("limit over", os.putenv, "B", "v" * 32766)
    attempt("limit unset ok", os.unsetenv, "B" * 32766)
    attempt("limit unset over", os.unsetenv, "B" * 32767)
    os.putenv("B", "")
    attempt("surrogate", os.putenv, "\ud800", "x")
    os.unsetenv("\ud800")
else:
    attempt("bytes", os.putenv, b"PYJVM_PUTENV_BY", b"v")
    attempt("bytes unset", os.unsetenv, b"PYJVM_PUTENV_BY")

# Child processes seeing putenv/unsetenv is checked in os_putenv_child.py (nt now; posix once _posixsubprocess is real).
