# types.GenericAlias(origin, args) renders the origin like CPython's ga_repr: module-qualified except builtins.
import types, collections, collections.abc, re, os

class Field:   # stands in for dataclasses.Field (importing dataclasses is not supported on the 3.13 lane)
    __module__ = 'dataclasses'
    __class_getitem__ = classmethod(types.GenericAlias)

dataclasses = type('dataclasses', (), {'Field': Field})
print(repr(types.GenericAlias(dataclasses.Field, (str,))))
print(repr(types.GenericAlias(list, (int,))))
print(repr(types.GenericAlias(list, int)))
print(repr(types.GenericAlias(re.Pattern, (str,))))
print(repr(types.GenericAlias(collections.abc.Callable, (int, str))))
print(repr(types.GenericAlias(os.PathLike, (str,))))
print(repr(types.GenericAlias(dict, (str, list[int]))))
print(repr(types.GenericAlias(list, (..., ))))
print(repr(types.GenericAlias(list, ((),))))
print(repr(types.GenericAlias(list, ([int, str],))))
print(repr(types.GenericAlias(type(None), (int,))))   # NoneType[int] before 3.14, None[int] after
print(repr(types.GenericAlias(int, (None,))))
class C: pass
print(repr(types.GenericAlias(C, (int,))))
print(Field.__module__, Field.__qualname__)
print(repr(list[dataclasses.Field[str]]) if hasattr(dataclasses.Field,'__class_getitem__') else 'no cgi')
print(repr(dataclasses.Field[str]))
print(repr(types.GenericAlias(len, (int,))))
a = types.GenericAlias(dataclasses.Field, (str,))
print(a.__origin__, a.__args__, a.__module__ if hasattr(a,'__module__') else None)
print(repr(types.GenericAlias(collections.OrderedDict, (str, int))))
print(repr(types.GenericAlias(collections.abc.Mapping, (str, int))))
