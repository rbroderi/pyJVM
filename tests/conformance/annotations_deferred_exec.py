# pyjvm: requires-python >= 3.14
# Module-level __annotate__ and __conditional_annotations__ through exec().
ns = {}
exec("x: undefined = 1", ns)
anno = ns["__annotate__"]
print(sorted(k for k in ns if k.startswith("__a") or k.startswith("__c")))
try:
    anno(3)
except NotImplementedError:
    print("NotImplementedError")
try:
    anno(1)
except NameError:
    print("NameError")
ns["undefined"] = 1
print(anno(1), anno.__name__, anno.__qualname__)
print("__annotations__" in ns)

ns = {"cond": False}
exec("""
a: int
if cond:
    b: str
else:
    c: bytes
d: 'last'
""", ns)
print(ns["__annotate__"](1), sorted(ns["__conditional_annotations__"]))

ns = {"cond": True}
exec("""
a: int
if cond:
    b: str
else:
    c: bytes
""", ns)
print(ns["__annotate__"](1), sorted(ns["__conditional_annotations__"]))

ns = {}
exec("x: [y for y in range(4)]", ns)
print(ns["__annotate__"](1))

ns = {}
exec("""
class C:
    __classdict__: int
""", ns)
print(ns["C"].__annotations__)

ns = {}
exec("from __future__ import annotations\nx: int\n", ns)
print(ns["__annotations__"], "__annotate__" in ns)

ns = {}
exec("x: int\ny = 1\nz: str = 'v'\n", ns)
print("x" in ns, ns["y"], ns["z"], ns["__annotate__"](1))

# annotation-only statements on non-simple targets evaluate the target but never the annotation
ns = {}
exec("""
class Box: pass
b = Box()
b.attr: undefined_annotation
lst = [0]
lst[0]: also_undefined
(paren): still_undefined
""", ns)
print("ok", "__annotate__" in ns)

# a module without annotations gets no __annotate__
ns = {}
exec("y = 1", ns)
print("__annotate__" in ns, "__conditional_annotations__" in ns)

# user code may define its own __annotate__ (and the module keeps working)
ns = {}
exec("""
a: int
def __annotate__(format):
    return {"a": str}
""", ns)
print(ns["__annotate__"](1))
