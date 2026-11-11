import sys

log = []


def global_tracer(frame, event, arg):
    name = frame.f_code.co_name
    if name == "ignored":
        return None
    log.append(("global", name, event))
    return local_tracer


def local_tracer(frame, event, arg):
    name = frame.f_code.co_name
    log.append(("local", name, event, frame.f_lineno - frame.f_code.co_firstlineno))
    if name == "stops_after_first_line" and event == "line":
        return None
    return local_tracer


def ignored():
    a = 1
    return a


def stops_after_first_line():
    a = 1
    b = 2
    return a + b


def loops():
    n = 0
    while n < 2:
        n += 1
    for k in (1, 2):
        n += k
    if n > 3:
        n = 0
    else:
        n = 1
    return n


def handlers():
    try:
        raise ValueError("x")
    except KeyError:
        return 1
    except ValueError:
        return 2


def finally_case():
    try:
        v = 1
    finally:
        v = 2
    return v


sys.settrace(global_tracer)
ignored()
stops_after_first_line()
loops()
handlers()
finally_case()
sys.settrace(None)
for entry in log:
    print(entry)
print(sys.gettrace())
