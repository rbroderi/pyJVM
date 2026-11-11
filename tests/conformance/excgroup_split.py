# subgroup / split / derive: matching conditions, nesting, metadata preservation
def tree(e, ind=0):
    if e is None:
        print(" " * ind + "None")
        return
    if isinstance(e, BaseExceptionGroup):
        print(" " * ind + "%s(%r)" % (type(e).__name__, e.message))
        for s in e.exceptions:
            tree(s, ind + 2)
    else:
        print(" " * ind + "%s%r" % (type(e).__name__, e.args))


leaves = [ValueError(1), TypeError(2), OSError(3), KeyError(4)]
eg = ExceptionGroup("top", [leaves[0], ExceptionGroup("mid", [leaves[1], leaves[2]]), leaves[3]])

tree(eg.subgroup(ValueError))
tree(eg.subgroup((ValueError, KeyError)))
tree(eg.subgroup(lambda e: isinstance(e, OSError)))
tree(eg.subgroup(ZeroDivisionError))
m, r = eg.split(TypeError)
tree(m)
tree(r)
m, r = eg.split(Exception)
print(m is eg, r)
m, r = eg.split(lambda e: not isinstance(e, BaseExceptionGroup))
print(m is eg, r)
m, r = eg.split(ExceptionGroup)
print(m is eg, r)
m, r = eg.split(lambda e: isinstance(e, ExceptionGroup))
print(m is eg, r)
m, r = eg.split(lambda e: isinstance(e, (ValueError, ExceptionGroup)))
print(m is eg, r)
# leaf identity is preserved
m, r = eg.split(OSError)
print(m.exceptions[0].exceptions[0] is leaves[2], r.exceptions[0] is leaves[0])

# predicate sees groups before leaves
seen = []
eg.split(lambda e: seen.append(type(e).__name__) or False)
print(seen)
seen = []
eg.subgroup(lambda e: seen.append(type(e).__name__) or False)
print(seen)

for bad in (1, "x", int, [ValueError], (ValueError, 3), None):
    try:
        eg.split(bad)
    except TypeError as e:
        print("TypeError", e)
    try:
        eg.subgroup(bad)
    except TypeError as e:
        print("TypeError", e)

# metadata is copied onto derived groups
try:
    raise eg
except ExceptionGroup as caught:
    tb = caught.__traceback__
cause = RuntimeError("cause")
ctx = LookupError("ctx")
eg2 = ExceptionGroup("meta", [ValueError(1), TypeError(2)])
eg2.__cause__ = cause
eg2.__context__ = ctx
eg2.add_note("note1")
eg2.add_note("note2")
try:
    raise eg2
except ExceptionGroup as c:
    tb2 = c.__traceback__
sub = eg2.subgroup(ValueError)
print(sub is not eg2, sub.__cause__ is cause, sub.__context__ is ctx, sub.__traceback__ is tb2)
print(sub.__notes__, sub.__notes__ is eg2.__notes__)
m, r = eg2.split(TypeError)
print(r.__cause__ is cause, r.__context__ is ctx, m.__traceback__ is tb2, r.__notes__)
print(eg2.subgroup(RuntimeError))

# derive
d = eg2.derive([OSError(9)])
print(type(d).__name__, d.message, d.exceptions, d.__cause__, d.__context__, d.__traceback__, getattr(d, "__notes__", None))
d = BaseExceptionGroup("b", [KeyboardInterrupt()]).derive([ValueError()])
print(type(d).__name__)
d = BaseExceptionGroup("b", [ValueError()]).derive([KeyboardInterrupt()])
print(type(d).__name__)
try:
    eg2.derive(5)
except Exception as e:
    print(type(e).__name__)
# a base group
beg = BaseExceptionGroup("base", [KeyboardInterrupt(), ValueError(1), ExceptionGroup("in", [OSError(2)])])
m, r = beg.split(Exception)
tree(m)
print(type(m).__name__)
tree(r)
print(type(r).__name__)
tree(beg.subgroup(KeyboardInterrupt))
print(type(beg.subgroup(KeyboardInterrupt)).__name__, type(beg.subgroup(OSError)).__name__)
