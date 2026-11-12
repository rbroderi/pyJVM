import io
import os
import sys
import traceback

HERE = os.path.basename(__file__)


def show(text):
    # file names are printed as given on the command line: keep only the base name
    print(text.replace(__file__, HERE), end="")


def leaf(n):
    raise ValueError("boom %d" % n)


def middle(n):
    return leaf(n) + 1


try:
    middle(3)
except ValueError:
    show(traceback.format_exc())
    buf = io.StringIO()
    traceback.print_exc(file=buf)
    show(buf.getvalue())

print("--- chained")
try:
    try:
        {}["missing"]
    except KeyError as exc:
        raise RuntimeError("wrapped") from exc
except RuntimeError as exc:
    show("".join(traceback.format_exception(exc)))
    print(traceback.format_exception_only(exc))
    print(traceback.format_exception_only(type(exc), exc))

print("--- implicit context")
try:
    try:
        int("x")
    except ValueError:
        leaf(7)
except ValueError:
    show(traceback.format_exc(limit=1))
    show(traceback.format_exc(chain=False))

print("--- extract_tb / StackSummary")
try:
    middle(1)
except ValueError as exc:
    frames = traceback.extract_tb(exc.__traceback__)
    print([(os.path.basename(f.filename), f.lineno, f.name, f.line) for f in frames])
    show("".join(traceback.format_list(frames)))
    show("".join(traceback.format_tb(exc.__traceback__)))
    te = traceback.TracebackException.from_exception(exc)
    print(te.exc_type_str if hasattr(te, "exc_type_str") else "ValueError", str(te))
    show("".join(te.format()))

print("--- no exception")
print(repr(traceback.format_exc()))

print("--- notes and groups")
try:
    err = TypeError("bad")
    err.add_note("a note")
    raise err
except TypeError:
    show(traceback.format_exc())


def stack_here():
    return traceback.extract_stack(limit=2)


names = [f.name for f in stack_here()]
print(names)
