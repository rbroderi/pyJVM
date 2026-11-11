# traceback report format: chains, notes, exception-group trees (via sys.excepthook into a captured stderr)
import sys


class Capture:
    def __init__(self):
        self.parts = []

    def write(self, text):
        self.parts.append(text)
        return len(text)

    def flush(self):
        pass


def report(exc):
    saved = sys.stderr
    cap = Capture()
    sys.stderr = cap
    try:
        sys.excepthook(type(exc), exc, exc.__traceback__)
    finally:
        sys.stderr = saved
    text = "".join(cap.parts).replace(__file__, "FILE")
    print(text, end="")
    print("=" * 20)


def capture(fn):
    try:
        fn()
    except BaseException as exc:
        return exc


def raise_value():
    raise ValueError("boom")


def middle():
    raise_value()


def chained():
    try:
        middle()
    except ValueError as e:
        raise RuntimeError("wrapped") from e


def implicit():
    try:
        middle()
    except ValueError:
        1 / 0


def suppressed():
    try:
        middle()
    except ValueError:
        raise KeyError("k") from None


def notes():
    e = OSError("with notes")
    e.add_note("first note")
    e.add_note("second\nmultiline")
    raise e


def group():
    raise ExceptionGroup("multiple", [ValueError(1), TypeError("two"),
                                      ExceptionGroup("inner", [KeyError("k"), OSError("o")])])


def group_with_notes():
    eg = ExceptionGroup("noted", [ValueError("a")])
    eg.add_note("group note")
    eg.exceptions[0].add_note("leaf note")
    raise eg


def raised_group_member():
    try:
        raise ExceptionGroup("g", [ValueError("a"), TypeError("b")])
    except* ValueError:
        raise


def group_in_chain():
    try:
        raise ExceptionGroup("g", [ValueError("a")])
    except ExceptionGroup:
        raise RuntimeError("after group")


class Custom(Exception):
    pass


def custom():
    raise Custom("custom message")


def empty_message():
    raise Custom()


def subscript():
    d = {}
    return d["missing"]


def call_attr():
    "abc".index("z")


def binop():
    a = 1
    b = 0
    return a / b


def nested_calls():
    return list(map(lambda x: 1 / x, [1, 0]))


def wide_group():
    raise ExceptionGroup("wide", [ValueError(i) for i in range(17)])


for fn in (chained, implicit, suppressed, notes, group, group_with_notes, raised_group_member, group_in_chain,
           custom, empty_message, subscript, call_attr, binop, wide_group):
    report(capture(fn))

# the default hook is also available as sys.__excepthook__
print(sys.excepthook is sys.__excepthook__)
