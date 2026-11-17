// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-pyJVM-Linking-Exception
package pyjvm315.runtime;

/** Docstrings and text signatures of {@code _operator}, measured on CPython 3.13 / 3.14 / 3.15 (identical on all three; 3.14 adds is_none/is_not_none). */
final class OperatorDocs {
    private OperatorDocs() {}

    static final String MODULE_DOC = "Operator interface.\n\nThis module exports a set of functions implemented in C corresponding\nto the intrinsic operators of Python.  For example, operator.add(x, y)\nis equivalent to the expression x+y.  The function names are those\nused for special methods; variants without leading and trailing\n'__' are also provided for convenience.";

    /** {name, text signature, doc}. */
    static final String[][] FUNCS = {
        {"_compare_digest", "($module, a, b, /)", "Return 'a == b'.\n\nThis function uses an approach designed to prevent\ntiming analysis, making it appropriate for cryptography.\n\na and b must both be of the same type: either str (ASCII only),\nor any bytes-like object.\n\nNote: If a and b are of different lengths, or if an error occurs,\na timing attack could theoretically reveal information about the\ntypes and lengths of a and b--but not their values."},
        {"abs", "($module, a, /)", "Same as abs(a)."},
        {"add", "($module, a, b, /)", "Same as a + b."},
        {"and_", "($module, a, b, /)", "Same as a & b."},
        {"call", "($module, obj, /, *args, **kwargs)", "Same as obj(*args, **kwargs)."},
        {"concat", "($module, a, b, /)", "Same as a + b, for a and b sequences."},
        {"contains", "($module, a, b, /)", "Same as b in a (note reversed operands)."},
        {"countOf", "($module, a, b, /)", "Return the number of items in a which are, or which equal, b."},
        {"delitem", "($module, a, b, /)", "Same as del a[b]."},
        {"eq", "($module, a, b, /)", "Same as a == b."},
        {"floordiv", "($module, a, b, /)", "Same as a // b."},
        {"ge", "($module, a, b, /)", "Same as a >= b."},
        {"getitem", "($module, a, b, /)", "Same as a[b]."},
        {"gt", "($module, a, b, /)", "Same as a > b."},
        {"iadd", "($module, a, b, /)", "Same as a += b."},
        {"iand", "($module, a, b, /)", "Same as a &= b."},
        {"iconcat", "($module, a, b, /)", "Same as a += b, for a and b sequences."},
        {"ifloordiv", "($module, a, b, /)", "Same as a //= b."},
        {"ilshift", "($module, a, b, /)", "Same as a <<= b."},
        {"imatmul", "($module, a, b, /)", "Same as a @= b."},
        {"imod", "($module, a, b, /)", "Same as a %= b."},
        {"imul", "($module, a, b, /)", "Same as a *= b."},
        {"index", "($module, a, /)", "Same as a.__index__()"},
        {"indexOf", "($module, a, b, /)", "Return the first index of b in a."},
        {"inv", "($module, a, /)", "Same as ~a."},
        {"invert", "($module, a, /)", "Same as ~a."},
        {"ior", "($module, a, b, /)", "Same as a |= b."},
        {"ipow", "($module, a, b, /)", "Same as a **= b."},
        {"irshift", "($module, a, b, /)", "Same as a >>= b."},
        {"is_", "($module, a, b, /)", "Same as a is b."},
        {"is_none", "($module, a, /)", "Same as a is None."},
        {"is_not", "($module, a, b, /)", "Same as a is not b."},
        {"is_not_none", "($module, a, /)", "Same as a is not None."},
        {"isub", "($module, a, b, /)", "Same as a -= b."},
        {"itruediv", "($module, a, b, /)", "Same as a /= b."},
        {"ixor", "($module, a, b, /)", "Same as a ^= b."},
        {"le", "($module, a, b, /)", "Same as a <= b."},
        {"length_hint", "($module, obj, default=0, /)", "Return an estimate of the number of items in obj.\n\nThis is useful for presizing containers when building from an iterable.\n\nIf the object supports len(), the result will be exact.\nOtherwise, it may over- or under-estimate by an arbitrary amount.\nThe result will be an integer >= 0."},
        {"lshift", "($module, a, b, /)", "Same as a << b."},
        {"lt", "($module, a, b, /)", "Same as a < b."},
        {"matmul", "($module, a, b, /)", "Same as a @ b."},
        {"mod", "($module, a, b, /)", "Same as a % b."},
        {"mul", "($module, a, b, /)", "Same as a * b."},
        {"ne", "($module, a, b, /)", "Same as a != b."},
        {"neg", "($module, a, /)", "Same as -a."},
        {"not_", "($module, a, /)", "Same as not a."},
        {"or_", "($module, a, b, /)", "Same as a | b."},
        {"pos", "($module, a, /)", "Same as +a."},
        {"pow", "($module, a, b, /)", "Same as a ** b."},
        {"rshift", "($module, a, b, /)", "Same as a >> b."},
        {"setitem", "($module, a, b, c, /)", "Same as a[b] = c."},
        {"sub", "($module, a, b, /)", "Same as a - b."},
        {"truediv", "($module, a, b, /)", "Same as a / b."},
        {"truth", "($module, a, /)", "Return True if a is true, False otherwise."},
        {"xor", "($module, a, b, /)", "Same as a ^ b."},
    };

    static final String DOC_ITEMGETTER = "Return a callable object that fetches the given item(s) from its operand.\nAfter f = itemgetter(2), the call f(r) returns r[2].\nAfter g = itemgetter(2, 5, 3), the call g(r) returns (r[2], r[5], r[3])";
    static final String SIG_ITEMGETTER = "(item, /, *items)";
    static final String DOC_ATTRGETTER = "Return a callable object that fetches the given attribute(s) from its operand.\nAfter f = attrgetter('name'), the call f(r) returns r.name.\nAfter g = attrgetter('name', 'date'), the call g(r) returns (r.name, r.date).\nAfter h = attrgetter('name.first', 'name.last'), the call h(r) returns\n(r.name.first, r.name.last).";
    static final String SIG_ATTRGETTER = "(attr, /, *attrs)";
    static final String DOC_METHODCALLER = "Return a callable object that calls the given method on its operand.\nAfter f = methodcaller('name'), the call f(r) returns r.name().\nAfter g = methodcaller('name', 'date', foo=1), the call g(r) returns\nr.name('date', foo=1).";
    static final String SIG_METHODCALLER = "(name, /, *args, **kwargs)";
}
