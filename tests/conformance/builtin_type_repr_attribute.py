# T.__repr__ exists on every builtin type and compares equal across lookups (pprint keys its dispatch table on it).
import collections
import sys
import types

kinds = [
    dict, list, tuple, set, frozenset, str, bytes, bytearray, int, float, complex, bool, object, type,
    types.MappingProxyType, types.SimpleNamespace, type({}.keys()), type({}.values()), type({}.items()),
    collections.OrderedDict, collections.defaultdict, collections.Counter, collections.deque, range, slice, memoryview,
]
if sys.version_info >= (3, 15):
    kinds.append(frozendict)
missing = []
for t in kinds:
    try:
        r = t.__repr__
    except AttributeError:
        missing.append(t.__name__)
        continue
    assert r == t.__repr__, t
print("missing:", missing)
table = {t.__repr__: index for index, t in enumerate(kinds)}
print(table[dict.__repr__] == 0, table[bool.__repr__] == kinds.index(bool), table[range.__repr__] == kinds.index(range), len(table) == len(kinds))
print(dict.__repr__({1: 2}), list.__repr__([1]), tuple.__repr__((1,)), bool.__repr__(True), range.__repr__(range(3)))
print(str.__repr__("a"), bytes.__repr__(b"a"), int.__repr__(5), type.__repr__(int))
