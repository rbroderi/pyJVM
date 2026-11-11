import _string
def p(s):
    try:
        print(list(_string.formatter_parser(s)))
    except Exception as e:
        print(type(e).__name__, e)
for s in ['', 'abc', '{}', '{0}', 'a{b}c', 'a{{b}}c', '{a!r:>5}', '{a:{b}}', '{!s}', '{a.b[0]}', '}}', '{{', '{', '}', '{a', '{a!', '{a!r', '{a!rx}', '{a:', 'x{:}y', '{:{}{}}', '{0!r:}', '{a[}']:
    p(s)
def q(s):
    try:
        first, rest = _string.formatter_field_name_split(s)
        print(repr(first), list(rest))
    except Exception as e:
        print(type(e).__name__, e)
for s in ['', '0', 'a', 'a.b', 'a[0]', 'a[0].b[x]', '0.x', '[0]', '.a', 'a.', 'a[', 'a[0', 'a[0]b', '1a', '00', 'a..b', 'a[]', 'a.b.c']:
    q(s)
try:
    _string.formatter_parser(1)
except Exception as e:
    print(type(e).__name__)
