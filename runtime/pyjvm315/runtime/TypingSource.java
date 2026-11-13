// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** The Python source of the {@code _typing} module (the C accelerator typing.py imports its type-variable classes from). */
final class TypingSource {
    private TypingSource() {}

    static final String TEXT = """
import sys


def _idfunc(_, x):
    return x


class NoDefaultType:
    __slots__ = ()
    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = object.__new__(cls)
        return cls._instance

    def __repr__(self):
        return 'typing.NoDefault'

    def __reduce__(self):
        return 'NoDefault'


NoDefault = NoDefaultType()
NoDefaultType.__module__ = 'typing'


def _caller_module():
    try:
        return sys._getframe(2).f_globals.get('__name__', '__main__')
    except Exception:
        return '__main__'


def _union(a, b):
    import typing
    return typing.Union[a, b]


class _TypeVarLike:
    def __or__(self, right):
        return _union(self, right)

    def __ror__(self, left):
        return _union(left, self)

    def __mro_entries__(self, bases):
        raise TypeError('Cannot subclass an instance of ' + type(self).__name__)

    def __reduce__(self):
        return self.__name__

    def has_default(self):
        return self.__default__ is not NoDefault


class TypeVar(_TypeVarLike):
    def __init__(self, name, *constraints, bound=None, covariant=False, contravariant=False,
                 infer_variance=False, default=NoDefault):
        if not isinstance(name, str):
            raise TypeError('TypeVar() argument 1 must be str, not ' + type(name).__name__)
        if covariant and contravariant:
            raise ValueError('Bivariant type variables are not supported.')
        if infer_variance and (covariant or contravariant):
            raise ValueError('Variance cannot be specified with infer_variance.')
        if constraints and bound is not None:
            raise TypeError('Constraints cannot be combined with bound=...')
        if len(constraints) == 1:
            raise TypeError('A single constraint is not allowed')
        self.__name__ = name
        self.__bound__ = _type_check_arg(bound, 'Bound must be a type.') if bound is not None else None
        self.__constraints__ = tuple(_type_check_arg(c, 'TypeVar(name, constraint, ...): constraints must be types.') for c in constraints)
        self.__covariant__ = bool(covariant)
        self.__contravariant__ = bool(contravariant)
        self.__infer_variance__ = bool(infer_variance)
        self.__default__ = default
        self.__module__ = _caller_module()

    def __repr__(self):
        if self.__infer_variance__:
            prefix = ''
        elif self.__covariant__:
            prefix = '+'
        elif self.__contravariant__:
            prefix = '-'
        else:
            prefix = '~'
        return prefix + self.__name__

    def __typing_subst__(self, arg):
        import typing
        return typing._typevar_subst(self, arg)


def _type_check_arg(arg, msg):
    import typing
    return typing._type_check(arg, msg)


class ParamSpecArgs:
    def __init__(self, origin):
        self.__origin__ = origin

    def __repr__(self):
        return self.__origin__.__name__ + '.args'

    def __eq__(self, other):
        if not isinstance(other, ParamSpecArgs):
            return NotImplemented
        return self.__origin__ == other.__origin__

    def __hash__(self):
        return hash((ParamSpecArgs, self.__origin__))

    def __mro_entries__(self, bases):
        raise TypeError('Cannot subclass an instance of ParamSpecArgs')


class ParamSpecKwargs:
    def __init__(self, origin):
        self.__origin__ = origin

    def __repr__(self):
        return self.__origin__.__name__ + '.kwargs'

    def __eq__(self, other):
        if not isinstance(other, ParamSpecKwargs):
            return NotImplemented
        return self.__origin__ == other.__origin__

    def __hash__(self):
        return hash((ParamSpecKwargs, self.__origin__))

    def __mro_entries__(self, bases):
        raise TypeError('Cannot subclass an instance of ParamSpecKwargs')


class ParamSpec(_TypeVarLike):
    def __init__(self, name, *, bound=None, covariant=False, contravariant=False,
                 infer_variance=False, default=NoDefault):
        if covariant and contravariant:
            raise ValueError('Bivariant type variables are not supported.')
        self.__name__ = name
        self.__bound__ = _type_check_arg(bound, 'Bound must be a type.') if bound is not None else None
        self.__covariant__ = bool(covariant)
        self.__contravariant__ = bool(contravariant)
        self.__infer_variance__ = bool(infer_variance)
        self.__default__ = default
        self.__module__ = _caller_module()

    @property
    def args(self):
        return ParamSpecArgs(self)

    @property
    def kwargs(self):
        return ParamSpecKwargs(self)

    def __repr__(self):
        if self.__infer_variance__:
            prefix = ''
        elif self.__covariant__:
            prefix = '+'
        elif self.__contravariant__:
            prefix = '-'
        else:
            prefix = '~'
        return prefix + self.__name__

    def __typing_subst__(self, arg):
        import typing
        return typing._paramspec_subst(self, arg)

    def __typing_prepare_subst__(self, alias, args):
        import typing
        return typing._paramspec_prepare_subst(self, alias, args)


class TypeVarTuple(_TypeVarLike):
    def __init__(self, name, *, default=NoDefault):
        self.__name__ = name
        self.__default__ = default
        self.__module__ = _caller_module()

    def __iter__(self):
        import typing
        yield typing.Unpack[self]

    def __repr__(self):
        return self.__name__

    def __typing_subst__(self, arg):
        raise TypeError('Substitution of bare TypeVarTuple is not supported')

    def __typing_prepare_subst__(self, alias, args):
        import typing
        return typing._typevartuple_prepare_subst(self, alias, args)


class TypeAliasType:
    def __init__(self, name, value, *, type_params=()):
        if not isinstance(name, str):
            raise TypeError('TypeAliasType() argument 1 must be str, not ' + type(name).__name__)
        self.__name__ = name
        self.__value__ = value
        self.__type_params__ = tuple(type_params)
        self.__parameters__ = tuple(type_params)
        self.__module__ = _caller_module()

    def __repr__(self):
        return self.__name__

    def __getitem__(self, parameters):
        import types
        if not isinstance(parameters, tuple):
            parameters = (parameters,)
        return types.GenericAlias(self, parameters)

    def __or__(self, right):
        return _union(self, right)

    def __ror__(self, left):
        return _union(left, self)

    def __reduce__(self):
        return self.__name__

    def __mro_entries__(self, bases):
        raise TypeError('Cannot subclass an instance of TypeAliasType')


class Generic:
    __slots__ = ()
    _is_protocol = False

    def __class_getitem__(cls, params):
        import typing
        return typing._generic_class_getitem(cls, params)

    def __init_subclass__(cls, *args, **kwargs):
        import typing
        return typing._generic_init_subclass(cls, *args, **kwargs)


Generic.__module__ = 'typing'
for _cls in (TypeVar, ParamSpec, TypeVarTuple, ParamSpecArgs, ParamSpecKwargs, TypeAliasType):
    _cls.__module__ = 'typing'
""";
}
