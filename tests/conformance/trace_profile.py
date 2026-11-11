import sys

events = []


def prof(frame, event, arg):
    if frame.f_code.co_name in ("prof", "<module>"):
        return
    events.append((frame.f_code.co_name, event, arg if event == "return" else None))


def leaf(x):
    return x * 2


def branchy(n):
    total = 0
    for i in range(n):
        total += leaf(i)
    return total


def boom():
    raise KeyError("k")


def catcher():
    try:
        boom()
    except KeyError:
        return "caught"


def gen():
    yield "a"
    yield "b"


def drive():
    return [v for v in gen()]


sys.setprofile(prof)
branchy(2)
catcher()
drive()
sys.setprofile(None)
for e in events:
    print(e)
print(sys.getprofile())
sys.setprofile(prof)
print(sys.getprofile() is prof)
sys.setprofile(None)
