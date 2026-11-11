# try/except* in generators, coroutines, loops, finally; sys.exception() inside handlers; syntax errors
import sys

def gen():
    try:
        raise ExceptionGroup("g", [ValueError(1), TypeError(2)])
    except* ValueError as e:
        yield ("V", len(e.exceptions))
        yield sys.exception() is not None
    except* TypeError as e:
        yield ("T", len(e.exceptions))
    yield "done"

print(list(gen()))

def gen2():
    try:
        yield 1
        yield 2
    except* GeneratorExit:
        print("closed")
        raise
    finally:
        print("fin")

g = gen2()
next(g)
try:
    g.close()
except BaseException as ex:
    print(type(ex).__name__)

def thrown():
    try:
        try:
            yield 1
        except* ValueError as e:
            yield ("caught", type(e).__name__)
    finally:
        yield "finally"

t = thrown()
next(t)
print(t.throw(ValueError("x")))
print(next(t))

async def co():
    try:
        await Yield()
        raise ExceptionGroup("g", [ValueError(1), KeyError(2)])
    except* ValueError as e:
        await Yield()
        print("co V", len(e.exceptions))
    except* KeyError as e:
        print("co K", len(e.exceptions))


class Yield:
    def __await__(self):
        yield


c = co()
try:
    while True:
        c.send(None)
except StopIteration:
    pass


# try/except* as the last statement of loops, with continue/break around (not inside) it
for i in range(4):
    if i == 1:
        continue
    try:
        if i == 2:
            raise ExceptionGroup("g", [ValueError(i)])
    except* ValueError as e:
        print("handled", i)
    if i == 3:
        break

# return inside a function nested in a handler is fine
try:
    raise ValueError(1)
except* ValueError:
    def inner():
        return 5
    print(inner())

# finally runs and return in try body
def early():
    try:
        return "body"
    except* ValueError:
        print("never")
    finally:
        print("cleanup")
print(early())

def swallow():
    try:
        raise ValueError(1)
    except* ValueError:
        pass
    return "after"
print(swallow())

# exception state inside and after handlers
print(sys.exception())
try:
    raise ExceptionGroup("g", [ValueError(1)])
except* ValueError as e:
    print(type(sys.exception()).__name__)
print(sys.exception())

# notes survive the split
eg = ExceptionGroup("g", [ValueError(1), TypeError(2)])
eg.add_note("note")
try:
    raise eg
except* ValueError as e:
    print(e.__notes__)
except* TypeError as e:
    print(e.__notes__)

# syntax errors
for src in (
    "try:\n    pass\nexcept* ValueError:\n    return 1\n",
    "def f():\n    try:\n        pass\n    except* ValueError:\n        return 1\n",
    "for i in range(3):\n    try:\n        pass\n    except* ValueError:\n        break\n",
    "for i in range(3):\n    try:\n        pass\n    except* ValueError:\n        continue\n",
    "try:\n    pass\nexcept* ValueError:\n    pass\nexcept TypeError:\n    pass\n",
    "try:\n    pass\nexcept ValueError:\n    pass\nexcept* TypeError:\n    pass\n",
    "try:\n    pass\nexcept*:\n    pass\n",
    "try:\n    pass\nexcept* (ValueError, TypeError) as e:\n    pass\n",
    "for i in range(3):\n    try:\n        pass\n    except* ValueError:\n        for j in range(2):\n            break\n",
    "def f():\n    try:\n        pass\n    except* ValueError:\n        def g():\n            return 1\n",
):
    try:
        compile(src, "<s>", "exec")
        print("ok")
    except SyntaxError as exc:
        print("SyntaxError", exc.msg)
