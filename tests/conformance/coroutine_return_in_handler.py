# SPDX-License-Identifier: GPL-3.0-or-later
# `return` inside a try/except or with that holds no yield, in a coroutine or generator that has already suspended.


class E(Exception):
    pass


class Y:
    def __await__(self):
        yield "suspended"
        return None


class CM:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        print("exit")
        return False


async def raises_after_wait():
    await Y()
    raise E("x")


async def t1():
    try:
        await raises_after_wait()
    except E as e:
        return e.args[0]


async def t2():
    await Y()
    try:
        raise E("y")
    except E:
        return 6


async def t3():
    await Y()
    try:
        raise E("y")
    except E as e:
        return e.args[0]
    finally:
        print("finally")


async def t4():
    await Y()
    with CM():
        return 7


async def t5():
    await Y()
    for i in range(3):
        try:
            if i == 1:
                return i
        finally:
            print("cleanup", i)


def g1():
    yield 1
    try:
        raise E("z")
    except E:
        return 8


async def t6():
    try:
        await Y()
        x = 1
    finally:
        print("outer finally")
    try:
        raise E("q")
    except E:
        return 9


def drive(c):
    out = []
    try:
        for _ in range(4):
            out.append(c.send(None))
    except StopIteration as s:
        out.append(("stop", s.value))
    return out


for fn in (t1, t2, t3, t4, t5, t6, g1):
    print(fn.__name__, drive(fn()))
