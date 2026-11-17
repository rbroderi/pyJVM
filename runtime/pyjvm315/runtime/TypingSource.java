// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** The Python source of the {@code _typing} module (the C accelerator typing.py imports its type-variable classes from). */
final class TypingSource {
    private TypingSource() {}

    static final String TEXT = """
import sys

_PY314 = sys.version_info >= (3, 14)
_PY315 = sys.version_info >= (3, 15)


def _idfunc(_, x):
    return x


class NoDefaultType:
    __slots__ = ()

    def __new__(cls, *args, **kwargs):
        if args or kwargs:
            raise TypeError('NoDefaultType takes no arguments')
        return NoDefault

    def __repr__(self):
        return 'typing.NoDefault'

    def __reduce__(self):
        return 'NoDefault'


NoDefault = object.__new__(NoDefaultType)
NoDefaultType.__module__ = 'typing'


if _PY314:
    Union = type(int | str)


def _caller_module():
    try:
        return sys._getframe(2).f_globals.get('__name__', '__main__')
    except Exception:
        return '__main__'


def _union(a, b):
    import typing
    return typing.Union[a, b]


def _check_kw(fn, kwargs, allowed):
    for k in kwargs:
        if k != 'name' and k not in allowed:
            raise TypeError("%s() got an unexpected keyword argument '%s'" % (fn, k))


def _name_arg(fn, args, kwargs):
    if args:
        if 'name' in kwargs:
            raise TypeError("argument for %s() given by name ('name') and position (1)" % fn)
        name = args[0]
    elif 'name' in kwargs:
        name = kwargs['name']
    else:
        raise TypeError("%s() missing required argument 'name' (pos 1)" % fn)
    if not isinstance(name, str):
        raise TypeError("%s() argument 'name' must be str, not %s" % (fn, type(name).__name__))
    return name


_READONLY = frozenset(('__bound__', '__constraints__', '__covariant__', '__contravariant__',
                       '__infer_variance__', '__default__', '__value__', '__type_params__', '__parameters__'))


def _var_setattr(self, name, value):
    if name == '__name__':
        raise AttributeError('readonly attribute')
    if name in _READONLY:
        raise AttributeError("attribute '%s' of 'typing.%s' objects is not writable" % (name, type(self).__name__))
    object.__setattr__(self, name, value)


def _init_variance(self, kwargs):
    covariant = kwargs.get('covariant', False)
    contravariant = kwargs.get('contravariant', False)
    infer = kwargs.get('infer_variance', False)
    if covariant and contravariant:
        raise ValueError('Bivariant types are not supported.')
    if infer and (covariant or contravariant):
        raise ValueError('Variance cannot be specified with infer_variance.')
    bound = kwargs.get('bound')
    object.__setattr__(self, '__bound__', _type_check_arg(bound, 'Bound must be a type.') if bound is not None else None)
    object.__setattr__(self, '__covariant__', bool(covariant))
    object.__setattr__(self, '__contravariant__', bool(contravariant))
    object.__setattr__(self, '__infer_variance__', bool(infer))
    object.__setattr__(self, '__default__', kwargs.get('default', NoDefault))


def _variance_repr(self):
    if self.__infer_variance__:
        prefix = ''
    elif self.__covariant__:
        prefix = '+'
    elif self.__contravariant__:
        prefix = '-'
    else:
        prefix = '~'
    return prefix + self.__name__


class _TypeVarLike:
    def __setattr__(self, name, value):
        _var_setattr(self, name, value)

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
    def __init__(self, *args, **kwargs):
        _check_kw('typevar', kwargs, ('bound', 'covariant', 'contravariant', 'infer_variance', 'default'))
        name = _name_arg('typevar', args, kwargs)
        constraints = args[1:]
        if constraints and kwargs.get('bound') is not None:
            raise TypeError('Constraints cannot be combined with bound=...')
        if len(constraints) == 1:
            raise TypeError('A single constraint is not allowed')
        _init_variance(self, kwargs)
        object.__setattr__(self, '__name__', name)
        object.__setattr__(self, '__constraints__', tuple(constraints))
        object.__setattr__(self, '__module__', _caller_module())

    def __repr__(self):
        return _variance_repr(self)

    def __typing_subst__(self, arg):
        import typing
        return typing._typevar_subst(self, arg)


def _type_check_arg(arg, msg):
    import typing
    return typing._type_check(arg, msg)


def _no_attrs(self, name, value):
    raise AttributeError("'typing.%s' object has no attribute '%s' and no __dict__ for setting new attributes" % (type(self).__name__, name))


class ParamSpecArgs:
    def __setattr__(self, name, value):
        _no_attrs(self, name, value)

    def __init__(self, origin):
        object.__setattr__(self, '__origin__', origin)

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
    def __setattr__(self, name, value):
        _no_attrs(self, name, value)

    def __init__(self, origin):
        object.__setattr__(self, '__origin__', origin)

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
    def __init__(self, *args, **kwargs):
        _check_kw('paramspec', kwargs, ('bound', 'covariant', 'contravariant', 'infer_variance', 'default'))
        if len(args) > 1:
            raise TypeError('paramspec() takes exactly 1 positional argument (%d given)' % len(args))
        name = _name_arg('paramspec', args, kwargs)
        _init_variance(self, kwargs)
        object.__setattr__(self, '__name__', name)
        object.__setattr__(self, '__module__', _caller_module())

    @property
    def args(self):
        return ParamSpecArgs(self)

    @property
    def kwargs(self):
        return ParamSpecKwargs(self)

    def __repr__(self):
        return _variance_repr(self)

    def __typing_subst__(self, arg):
        import typing
        return typing._paramspec_subst(self, arg)

    def __typing_prepare_subst__(self, alias, args):
        import typing
        return typing._paramspec_prepare_subst(self, alias, args)


class TypeVarTuple(_TypeVarLike):
    def __init__(self, *args, **kwargs):
        if _PY315:
            _check_kw('typevartuple', kwargs, ('bound', 'covariant', 'contravariant', 'infer_variance', 'default'))
        else:
            _check_kw('typevartuple', kwargs, ('default',))
        if len(args) > 1:
            raise TypeError('typevartuple() takes exactly 1 positional argument (%d given)' % len(args))
        name = _name_arg('typevartuple', args, kwargs)
        if _PY315:
            _init_variance(self, kwargs)
        else:
            object.__setattr__(self, '__default__', kwargs.get('default', NoDefault))
        object.__setattr__(self, '__name__', name)
        object.__setattr__(self, '__module__', _caller_module())

    def __iter__(self):
        import typing
        yield typing.Unpack[self]

    def __repr__(self):
        if _PY315:
            return _variance_repr(self)
        return self.__name__

    def __typing_subst__(self, arg):
        raise TypeError('Substitution of bare TypeVarTuple is not supported')

    def __typing_prepare_subst__(self, alias, args):
        import typing
        return typing._typevartuple_prepare_subst(self, alias, args)


def _alias_setattr(self, name, value):
    if name == '__module__':
        object.__setattr__(self, name, value)
        return
    if name == '__name__':
        raise AttributeError('readonly attribute')
    if name in _READONLY:
        raise AttributeError("attribute '%s' of 'typing.TypeAliasType' objects is not writable" % name)
    _no_attrs(self, name, value)


class TypeAliasType:
    def __setattr__(self, name, value):
        _alias_setattr(self, name, value)

    def __init__(self, *args, **kwargs):
        for k in kwargs:
            if k not in ('name', 'value', 'type_params'):
                raise TypeError("typealias() got an unexpected keyword argument '%s'" % k)
        if len(args) > 2:
            raise TypeError('typealias() takes exactly 2 positional arguments (%d given)' % len(args))
        vals = []
        for i, fname in enumerate(('name', 'value')):
            if i < len(args):
                if fname in kwargs:
                    raise TypeError("argument for typealias() given by name ('%s') and position (%d)" % (fname, i + 1))
                vals.append(args[i])
            elif fname in kwargs:
                vals.append(kwargs[fname])
            else:
                raise TypeError("typealias() missing required argument '%s' (pos %d)" % (fname, i + 1))
        name, value = vals
        if not isinstance(name, str):
            raise TypeError("typealias() argument 'name' must be str, not %s" % type(name).__name__)
        type_params = kwargs.get('type_params', ())
        if not isinstance(type_params, tuple):
            raise TypeError('type_params must be a tuple')
        if _PY314:
            for p in type_params:
                if type(p).__name__ not in ('TypeVar', 'ParamSpec', 'TypeVarTuple'):
                    raise TypeError('Expected a type param, got %r' % (p,))
        object.__setattr__(self, '__name__', name)
        object.__setattr__(self, '__value__', value)
        object.__setattr__(self, '__type_params__', type_params)
        object.__setattr__(self, '__parameters__', type_params)
        object.__setattr__(self, '__module__', _caller_module())

    def __repr__(self):
        return self.__name__

    def __getitem__(self, parameters):
        import types
        if not self.__type_params__:
            raise TypeError('Only generic type aliases are subscriptable')
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


def _generic_class_getitem_hook(cls, params):
    import typing
    return typing._generic_class_getitem(cls, params)


def _generic_init_subclass_hook(cls, *args, **kwargs):
    import typing
    return typing._generic_init_subclass(cls, *args, **kwargs)


for _cls in (TypeVar, ParamSpec, TypeVarTuple, ParamSpecArgs, ParamSpecKwargs, TypeAliasType):
    _cls.__module__ = 'typing'
""";
}
