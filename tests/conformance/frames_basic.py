import sys


def where():
    fr = sys._getframe()
    return fr.f_code.co_name, fr.f_lineno - fr.f_code.co_firstlineno


def caller():
    a = 1
    b = "x"
    me = sys._getframe()
    back = me.f_back
    return me.f_code.co_name, me.f_lineno - me.f_code.co_firstlineno, back.f_code.co_name, back.f_lineno - back.f_code.co_firstlineno, sorted(me.f_locals.keys())[:2], me.f_locals["a"]


def outer():
    return caller()


print(where())
print(outer())


def depth_chain():
    names = []
    fr = sys._getframe()
    while fr is not None:
        names.append(fr.f_code.co_name)
        fr = fr.f_back
    return names


def lvl2():
    return depth_chain()


def lvl1():
    return lvl2()


print(lvl1())


def up(n):
    return sys._getframe(n).f_code.co_name


def wrap():
    return up(1), up(0)


print(wrap())

g_value = 42


def globals_view():
    fr = sys._getframe()
    return fr.f_globals["g_value"], fr.f_code.co_filename.endswith(".py")


print(globals_view())


def gen():
    yield 1
    yield 2


g = gen()
print(g.gi_frame is not None, g.gi_frame.f_code.co_name)
next(g)
print(g.gi_frame.f_lineno - g.gi_frame.f_code.co_firstlineno)
list(g)
print(g.gi_frame)


async def co():
    return 1


c = co()
print(c.cr_frame.f_code.co_name)
try:
    c.send(None)
except StopIteration:
    pass
print(c.cr_frame)


def raiser():
    x = 5
    raise ValueError("v")


def tb_user():
    try:
        raiser()
    except ValueError as e:
        tb = e.__traceback__
        out = []
        while tb is not None:
            out.append((tb.tb_frame.f_code.co_name, tb.tb_lineno - tb.tb_frame.f_code.co_firstlineno))
            tb = tb.tb_next
        return out


print(tb_user())
