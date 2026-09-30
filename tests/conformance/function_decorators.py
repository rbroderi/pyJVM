events = []

def make(name):
    events.append("eval-" + name)
    def deco(fn):
        events.append("apply-" + name)
        def wrapped(x):
            return name + ":" + fn(x)
        return wrapped
    return deco

@make("top")
@make("bottom")
def f(x):
    return x

print(events)
print(f("ok"))

def outer(prefix):
    def deco(fn):
        def wrapper(x):
            return prefix + fn(x)
        return wrapper
    @deco
    def inner(x):
        return x
    return inner

print(outer("!")("yes"))
