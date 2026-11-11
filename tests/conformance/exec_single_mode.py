import sys
import builtins

# compile(..., "single") is interactive mode: an expression statement goes through sys.displayhook
exec(compile("1 + 2", "<i>", "single"))
exec(compile("'text'", "<i>", "single"))
exec(compile("None", "<i>", "single"))          # None is never displayed
ns = {}
exec(compile("x = 5", "<i>", "single"), ns)       # a statement displays nothing
exec(compile("x * 2", "<i>", "single"), ns)
print(ns["x"], builtins._)                        # the hook remembers the last value as builtins._

shown = []
previous = sys.displayhook
sys.displayhook = shown.append
try:
    exec(compile("[1, 2]", "<i>", "single"))
    exec(compile("for n in range(3):\n    n * 10\n", "<i>", "single"))
    exec(compile("if True:\n    'in if'\n", "<i>", "single"))
    exec(compile("def f():\n    99\n", "<i>", "single"), ns)
    ns["f"]()                                     # expression statements inside functions are not displayed
finally:
    sys.displayhook = previous
print(shown)

# the default hook is also reachable directly
sys.displayhook(41)
sys.displayhook(None)
print(sys.displayhook is sys.__displayhook__)

# eval-mode code returns the value instead of displaying it; exec mode never displays
print(eval(compile("6 * 7", "<e>", "eval")))
exec(compile("6 * 7", "<x>", "exec"))
print(type(compile("x = 1", "<i>", "single")).__name__)
