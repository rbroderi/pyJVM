print("b: start")
import circ.a as a_mod
from circ import a
from circ.a import A_EARLY

B_SEES_A_EARLY = A_EARLY
print("b: a is a_mod:", a is a_mod, "has A_LATE:", hasattr(a, "A_LATE"))
try:
    from circ.a import A_LATE
except ImportError as e:
    print("b:", type(e).__name__, str(e).replace(e.path, "<path>"), e.name, e.name_from)
B_VALUE = "b-value"
print("b: done")
