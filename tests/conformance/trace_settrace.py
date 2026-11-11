import sys

events = []
def tracer(frame, event, arg):
    name = frame.f_code.co_name
    if name in ("tracer", "<module>") and event != "call":
        pass
    if event == "exception":
        arg = (arg[0].__name__, str(arg[1]))
    events.append((name, event, frame.f_lineno - frame.f_code.co_firstlineno, arg if event in ("return", "exception") else None))
    return tracer

def thrower():
    raise ValueError("boom")

def mid():
    try:
        thrower()
    except ValueError:
        raise

def outer():
    try:
        mid()
    except ValueError:
        pass
    x = 1
    for i in range(2):
        x += i
    return x

def gen():
    yield 1
    yield 2

def usegen():
    for v in gen():
        pass

sys.settrace(tracer)
outer()
usegen()
sys.settrace(None)
for e in events:
    print(e)
