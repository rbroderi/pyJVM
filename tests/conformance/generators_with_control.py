# return/break/continue through a with block inside a yielding generator.
class CM:
    def __init__(self, name, suppress=False):
        self.name = name
        self.suppress = suppress
    def __enter__(self):
        print("enter", self.name)
        return self.name
    def __exit__(self, typ, value, tb):
        print("exit", self.name, typ.__name__ if typ else None)
        return self.suppress

def ret():
    with CM("a"):
        yield 1
        return "returned"
    yield "unreachable"

g = ret()
print(next(g))
try:
    next(g)
except StopIteration as e:
    print("stop", e.value)

def loops():
    for i in range(4):
        with CM("loop%d" % i):
            yield i
            if i == 1:
                continue
            if i == 2:
                break
        print("after with", i)
    else:
        print("no break")
    yield "done"

print(list(loops()))

def nested():
    with CM("outer"):
        with CM("inner"):
            yield "x"
            return 5

g = nested()
print(next(g))
try:
    next(g)
except StopIteration as e:
    print("nested stop", e.value)

def with_in_try():
    try:
        with CM("t"):
            yield 1
            return "v"
    finally:
        print("finally")

g = with_in_try()
next(g)
try:
    next(g)
except StopIteration as e:
    print(e.value)

class Bad:
    def __enter__(self):
        return self
    def __exit__(self, *a):
        print("bad exit", a[0])
        raise KeyError("from exit")

def exit_raises():
    with Bad():
        yield 1
        return 2

g = exit_raises()
next(g)
try:
    next(g)
except KeyError as e:
    print("caught", e.args)

def loop_inside_try():
    try:
        for i in range(3):
            yield i
            if i == 1:
                break
        print("after loop")
    finally:
        print("fin")
    yield "tail"

print(list(loop_inside_try()))

def return_value_through_two():
    with CM("one"):
        try:
            yield 1
            return "val"
        finally:
            print("inner finally")

g = return_value_through_two()
next(g)
try:
    next(g)
except StopIteration as e:
    print(e.value)
