import sys, pickle
import _typing as t

def show(label, fn):
    try:
        r = fn()
        print(label, '->', repr(r))
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)

print(sorted(n for n in dir(t) if not (n.startswith('__') and n.endswith('__'))))
for n in ('Generic', 'ParamSpec', 'ParamSpecArgs', 'ParamSpecKwargs', 'TypeAliasType', 'TypeVar', 'TypeVarTuple', ):
    o = getattr(t, n)
    print(n, getattr(o, '__module__', None), getattr(o, '__qualname__', None))
print('Union' in dir(t), type(t.NoDefault).__name__)

T = t.TypeVar('T'); P = t.ParamSpec('P'); Ts = t.TypeVarTuple('Ts')
print(repr(T), repr(t.TypeVar('U', covariant=True)), repr(t.TypeVar('V', contravariant=True)), repr(t.TypeVar('W', infer_variance=True)), repr(P), repr(Ts))
print(repr(t.ParamSpec('Q', covariant=True)), repr(t.ParamSpec('Q', contravariant=True)), repr(t.ParamSpec('Q', infer_variance=True)))
print(T.__module__, P.__module__, Ts.__module__, type(T).__name__, type(T).__qualname__, type(T).__module__)
print(type(P.args).__name__, type(P.kwargs).__name__, repr(P.args), repr(P.kwargs), P.args.__origin__ is P, P.args == P.args, P.args == P.kwargs)
print(type(P.args).__module__)
for c in (t.TypeVar, t.ParamSpec, t.TypeVarTuple, t.TypeAliasType, t.ParamSpecArgs, t.ParamSpecKwargs, t.Generic):
    def f(c=c):
        c.x = 1
    show('setattr ' + c.__name__, f)
    def g(c=c):
        class A(c): pass
    show('subclass ' + c.__name__, g)
for o in (T, P, Ts, t.ParamSpec('R').args):
    def g(o=o):
        class A(o): pass
    show('inst subclass ' + type(o).__name__, g)
show('TV bivariant', lambda: t.TypeVar('X', covariant=True, contravariant=True))
show('TV infer+cov', lambda: t.TypeVar('X', covariant=True, infer_variance=True))
show('TV infer+contra', lambda: t.TypeVar('X', contravariant=True, infer_variance=True))
show('TV bound+cons', lambda: t.TypeVar('X', int, str, bound=int))
show('TV single', lambda: t.TypeVar('X', int))
show('TV noname', lambda: t.TypeVar())
show('TV nonstr', lambda: t.TypeVar(1))
show('TV kwname', lambda: t.TypeVar(name='X'))
show('TV badkw', lambda: t.TypeVar('X', foo=1))
show('TV badbound', lambda: t.TypeVar('X', bound=1))
show('TV badcons', lambda: t.TypeVar('X', 1, 2))
show('TV bound', lambda: t.TypeVar('X', bound=int).__bound__)
show('TV cons', lambda: t.TypeVar('X', int, str).__constraints__)
show('TV default', lambda: t.TypeVar('X', default=int).__default__)
show('TV nodefault', lambda: (T.__default__, T.has_default(), t.TypeVar('X', default=int).has_default()))
show('TV badname name', lambda: t.TypeVar('X', bound='int').__bound__)
show('TV pos kw', lambda: t.TypeVar('X', None))
show('PS bivar', lambda: t.ParamSpec('X', covariant=True, contravariant=True))
show('PS infer', lambda: t.ParamSpec('X', covariant=True, infer_variance=True))
show('PS noname', lambda: t.ParamSpec())
show('PS nonstr', lambda: t.ParamSpec(1))
show('PS bad bound', lambda: t.ParamSpec('X', bound=1))
show('PS bound', lambda: t.ParamSpec('X', bound=int).__bound__)
show('PS pos', lambda: t.ParamSpec('X', int))
show('PS default', lambda: (P.__default__, P.has_default(), t.ParamSpec('X', default=[int]).has_default()))
show('TVT noname', lambda: t.TypeVarTuple())
show('TVT nonstr', lambda: t.TypeVarTuple(1))
show('TVT pos', lambda: t.TypeVarTuple('X', int))
show('TVT default', lambda: (Ts.__default__, Ts.has_default()))
show('TVT iter', lambda: list(Ts))
show('TVT bad kw', lambda: t.TypeVarTuple('X', bound=int))
show('TV mro', lambda: T.__mro_entries__(()))
show('TV subst', lambda: T.__typing_subst__(int))
show('TV or', lambda: (T | int, int | T))
show('TV reduce', lambda: (T.__reduce__(), pickle.loads(pickle.dumps(T)) if False else None))
show('TV copy', lambda: __import__('copy').copy(T) is T)
show('TV deepcopy', lambda: __import__('copy').deepcopy(T) is T)
show('PS copy', lambda: __import__('copy').copy(P) is P)
show('TV hash/eq', lambda: (T == T, T == t.TypeVar('T'), hash(T) == hash(T)))
show('TV str', lambda: str(T))
show('TV weakref', lambda: __import__('weakref').ref(T)() is T)
show('TV attr set name', lambda: setattr(T, '__name__', 'z'))
show('TV attr set bound', lambda: setattr(T, '__bound__', int))
show('TV attr set module', lambda: setattr(T, '__module__', 'zz'))
show('TV del module', lambda: delattr(T, '__module__'))
show('TV copy_with', lambda: hasattr(T, '__origin__'))
show('Ts unpack', lambda: (Ts.__typing_is_unpacked_typevartuple__ if hasattr(Ts, '__typing_is_unpacked_typevartuple__') else 'absent'))
show('Ts subst', lambda: Ts.__typing_subst__(int))
show('ND new', lambda: type(t.NoDefault)() is t.NoDefault)
show('ND reduce', lambda: t.NoDefault.__reduce__())
show('ND pickle', lambda: pickle.loads(pickle.dumps(t.NoDefault)) is t.NoDefault)
show('ND copy', lambda: __import__('copy').copy(t.NoDefault) is t.NoDefault)
show('ND bool', lambda: bool(t.NoDefault))
show('ND dict', lambda: hasattr(t.NoDefault, '__dict__'))
show('typing.NoDefault is', lambda: __import__('typing').NoDefault is t.NoDefault)
show('typing.TypeVar is', lambda: __import__('typing').TypeVar is t.TypeVar)
show('typing.Generic is', lambda: __import__('typing').Generic is t.Generic)
show('typing.TAT is', lambda: __import__('typing').TypeAliasType is t.TypeAliasType)
show('typing.PSA is', lambda: __import__('typing').ParamSpecArgs is t.ParamSpecArgs)
if sys.version_info >= (3, 14):
    show('typing.Union is', lambda: __import__('typing').Union is t.Union)
    show('Union types', lambda: __import__('types').UnionType is t.Union)
# TypeAliasType
A = t.TypeAliasType('A', int)
show('TAT', lambda: (repr(A), A.__name__, A.__value__, A.__type_params__, A.__parameters__, A.__module__))
B = t.TypeAliasType('B', list[T], type_params=(T,))
show('TAT B', lambda: (B.__type_params__, B.__parameters__, B[int], type(B[int]).__name__, B[int].__origin__ is B))
show('TAT B2', lambda: B[int, str])
show('TAT A[int]', lambda: A[int])
if sys.version_info >= (3, 14):
    show('TAT or', lambda: (A | int, int | A))
show('TAT reduce', lambda: A.__reduce__())
show('TAT pickle', lambda: pickle.loads(pickle.dumps(A)) is A)
show('TAT bad name', lambda: t.TypeAliasType(1, int))
show('TAT no value', lambda: t.TypeAliasType('A'))
show('TAT pos tp', lambda: t.TypeAliasType('A', int, ()))
show('TAT bad tp', lambda: t.TypeAliasType('A', int, type_params=1))
show('TAT bad tp2', lambda: t.TypeAliasType('A', int, type_params=(int,)))
show('TAT tp list', lambda: t.TypeAliasType('A', int, type_params=[T]).__type_params__)
show('TAT set', lambda: setattr(A, '__name__', 'x'))
show('TAT set value', lambda: setattr(A, '__value__', 1))
show('TAT set x', lambda: setattr(A, 'x', 1))
show('TAT hash', lambda: (A == A, hash(A) == hash(A)))
show('TAT copy', lambda: __import__('copy').copy(A) is A)
show('TAT Generic', lambda: t.Generic[T])
show('TAT Generic2', lambda: t.Generic[int])
show('TAT Generic3', lambda: t.Generic[T, T])
show('TAT Generic4', lambda: t.Generic[()])
show('Generic', lambda: (t.Generic.__module__, t.Generic.__qualname__, t.Generic.__mro__, repr(t.Generic)))
show('Generic()', lambda: type(t.Generic()).__name__)
show('Generic[T][int]', lambda: t.Generic[T][int])
show('Generic[T]', lambda: repr(t.Generic[T]))
show('Generic type', lambda: type(t.Generic[T]).__name__)
show('Generic params', lambda: t.Generic.__parameters__ if hasattr(t.Generic, '__parameters__') else 'absent')
class G(t.Generic[T]): pass
show('G', lambda: (G.__parameters__, G.__orig_bases__, G[int], G.__mro__, G.__bases__))
show('G()', lambda: type(G()).__name__)
show('G[int]()', lambda: type(G[int]()).__name__)
class H(t.Generic[T, P]): pass
show('H', lambda: (H.__parameters__,))
show('H[int]', lambda: H[int, [str]])
def mk():
    class K(t.Generic[int]): pass
show('class Generic[int]', mk)
def mk2():
    class K(t.Generic, t.Generic[T]): pass
def mk3():
    class K(t.Generic[T], t.Generic[T]): pass
def mk4():
    class K(list[T], t.Generic[()]): pass
def mk5():
    class K(t.Generic[T], x=1): pass
def mk6():
    class K(t.Generic): pass
    return K.__parameters__ if hasattr(K, '__parameters__') else 'absent', K.__orig_bases__ if hasattr(K, '__orig_bases__') else 'absent'
show('class bare', mk6)
show('module dunder', lambda: sorted(a for a in dir(t) if a.startswith('__')))
show('module', lambda: (t.__name__, t.__doc__, t.__spec__.name if t.__spec__ else None, t.__spec__.origin if t.__spec__ else None, t.__loader__.__name__ if t.__loader__ else None, getattr(t, '__file__', 'nofile'), getattr(t, '__package__', None)))
show('module repr', lambda: repr(t))
show('sys.modules', lambda: sys.modules['_typing'] is t)
# typing integration
import typing
show('typing T', lambda: (typing.List[T], typing.Dict[T, int][str], typing.Callable[P, T], typing.Callable[P, T][[int], str]))
show('typing Ts', lambda: (typing.Tuple[typing.Unpack[Ts]], typing.Tuple[int, *Ts][str, bytes] if False else None))
show('typing Ts2', lambda: typing.Tuple[typing.Unpack[Ts]][int, str])
show('typing Ts3', lambda: list(Ts)[0])
show('typing Ts4', lambda: (type(list(Ts)[0]).__name__, list(Ts)[0].__origin__))
show('typing PSA', lambda: (P.args, P.kwargs))
show('typing ParamSpec defaults', lambda: typing.Callable[P, int].__parameters__)
show('typing Generic[Ts]', lambda: G[int])
show('Tvar in Union', lambda: typing.Union[T, int])
show('T | None', lambda: T | None)
show('Optional[T]', lambda: typing.Optional[T])
show('Annotated', lambda: typing.Annotated[T, 1])
show('TypeVar repr typing', lambda: typing.TypeVar('Q', bound=int) )
show('TypeVar in cls', lambda: typing.TypeVar('Q', 'int', 'str').__constraints__)
show('TypeVar fwd', lambda: typing.TypeVar('Q', bound='Foo').__bound__)
show('bound none', lambda: typing.TypeVar('Q', bound=None).__bound__)
show('bound default', lambda: typing.TypeVar('Q', default=None).__default__)
show('ParamSpec default', lambda: typing.ParamSpec('Q', default=...).__default__)
show('TypeVarTuple default', lambda: typing.TypeVarTuple('Q', default=typing.Unpack[typing.Tuple[int]]).__default__)
show('tvt in subscript', lambda: typing.Tuple[*Ts])
# frame-dependent module
def f():
    return t.TypeVar('Z').__module__
show('module in fn', f)
show('module via typing', lambda: typing.TypeVar('Z').__module__)
if sys.version_info >= (3, 12):
    ns = {'__name__': 'surface_mod'}
    exec("""
class C[X, *Y, **Z]: pass
type TA[X] = list[X]
def f[X: int, W: (int, str) = int](): pass
""", ns)
    C = ns['C']; TA = ns['TA']; f = ns['f']
    show('pep695 C', lambda: (C.__type_params__, [type(p).__name__ for p in C.__type_params__], C.__bases__, C.__mro__, C.__parameters__))
    show('pep695 mod', lambda: [p.__module__ for p in C.__type_params__])
    show('pep695 TA', lambda: (TA, type(TA).__name__, TA.__module__, TA.__type_params__, TA.__value__, TA[int]))
    show('pep695 f', lambda: [(p.__name__, p.__bound__, p.__constraints__, p.__default__ if hasattr(p, '__default__') else 'noattr') for p in f.__type_params__])
    show('pep695 Generic', lambda: t.Generic in C.__mro__)
    show('pep695 repr', lambda: [repr(p) for p in C.__type_params__])
    show('pep695 evaluate', lambda: (f.__type_params__[0].__bound__, f.__type_params__[1].__constraints__))
    if sys.version_info >= (3, 14):
        pass
