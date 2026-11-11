print("a: start")
A_EARLY = "a-early"
from . import b
print("a: b loaded, b.B_SEES_A_EARLY =", b.B_SEES_A_EARLY)
A_LATE = "a-late"


def a_func():
    return "a_func sees b.B_VALUE=" + b.B_VALUE
