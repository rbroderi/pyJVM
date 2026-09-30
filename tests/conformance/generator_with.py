class CM:
    def __init__(self, suppress):
        self.suppress = suppress
    def __enter__(self):
        print("enter")
        return "resource"
    def __exit__(self, typ, value, tb):
        print("exit", typ is None)
        return self.suppress

def normal():
    with CM(False) as value:
        yield value
        yield "after"

n = normal()
print(next(n))
print(next(n))
try:
    next(n)
except StopIteration:
    print("normal-done")

def injected():
    with CM(True):
        yield "ready"
        yield "unreached"
    yield "suppressed"

x = injected()
print(next(x))
print(x.throw(ValueError("boom")))
try:
    next(x)
except StopIteration:
    print("injected-done")
