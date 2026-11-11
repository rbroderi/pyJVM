# pyjvm: requires-python >= 3.14
# Constructs CPython rejects inside annotations once they are deferred.
cases = [
    "def f(x: (yield)): pass",
    "def f(x) -> (yield): pass",
    "from __future__ import annotations\ndef f(x: (yield)): pass",
    "x: (y := 3)",
    "class C:\n a: (y := 3)",
    "def g():\n a: (y := 3)",
    "def f(x: (await 42)): pass",
    "x: [y := 1 for z in w]",
    "def f(x: int) -> str: pass",
]
for source in cases:
    try:
        compile(source, "<t>", "exec")
        print("OK ", repr(source))
    except SyntaxError as exc:
        print("ERR", repr(source), exc.args[0])
