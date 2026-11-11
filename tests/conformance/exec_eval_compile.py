g = {}
exec("x = 40\ny = x + 2\ndef add(a, b=1):\n    return a + b + x\nclass K:\n    v = y\n", g)
print(g["x"], g["y"], g["add"](1), g["add"](1, 2), g["K"].v, "__builtins__" in g)
g["x"] = 100
print(g["add"](0))                      # functions see later changes to their globals
exec("z = x * 2", g)
print(g["z"], "z" in g)

print(eval("1 + 2 * 3", {}), eval("x + 1", {"x": 9}), eval("[i * i for i in range(4)]", {}))
ns = {"n": 3}
print(eval("n ** 2", ns), eval(" n ", ns), ns == {"n": 3, "__builtins__": ns["__builtins__"]})

loc = {}
exec("a = 1\nb = a + 1", {}, loc)
print(loc)
gl = {"base": 10}
lc = {"extra": 5}
exec("r = base + extra", gl, lc)
print(lc.get("r"), "r" in gl)

code = compile("q = 7\nq * 2", "<test>", "exec")
h = {}
print(exec(code, h), h["q"], type(code).__name__)
expr = compile("a + b", "<expr>", "eval")
print(eval(expr, {"a": 1, "b": 2}), eval(expr, {"a": "x", "b": "y"}))
print(exec(compile("1 + 1", "<e>", "eval"), {}), eval(compile("w = 3", "<x>", "exec"), {}))

try:
    eval("1 +", {})
except SyntaxError as e:
    print("SyntaxError")
try:
    eval("nope_name", {})
except NameError as e:
    print("NameError", e)
try:
    eval("1 / 0", {})
except ZeroDivisionError as e:
    print("ZeroDivisionError")
try:
    eval("[][1]", {})
except IndexError as e:
    print("IndexError")

h2 = {}
try:
    exec("def f():\n    raise ValueError('boom')\nf()", h2)
except ValueError as e:
    print("ValueError", e)
print(sorted(k for k in h2 if not k.startswith("__")))

h3 = {"seen": []}
exec("""
def outer():
    total = 0
    def inner(n):
        nonlocal total
        total += n
        return total
    return inner
c = outer()
c(2); c(3)
seen.append(c(5))
global_flag = 'set'
def setter():
    global global_flag2
    global_flag2 = 'from function'
setter()
del global_flag
""", h3)
print(h3["seen"], "global_flag" in h3, h3["global_flag2"])

h4 = {}
exec("""
nested = {}
exec("inner_value = 99", nested)
result = nested["inner_value"]
direct = eval("result + 1")
""", h4)
print(h4["result"], h4["direct"])

print(eval("globals()['k']", {"k": "via globals()"}))
exec("import_free = 1\nprint(sorted(n for n in locals() if not n.startswith('__')))", {})

class Box:
    def __init__(self, v):
        self.v = v
print(eval("Box(3).v", {"Box": Box}), eval("len(items)", {"items": [1, 2, 3]}))
for bad in (5, None):
    try:
        exec(bad, {})
    except TypeError as e:
        print("TypeError")
try:
    exec("pass", [])
except TypeError:
    print("TypeError globals")
