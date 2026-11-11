import sys

mon = sys.monitoring
E = mon.events
TOOL = mon.PROFILER_ID
log = []


def on_start(code, offset):
    log.append(("start", code.co_name))


def on_resume(code, offset):
    log.append(("resume", code.co_name))


def on_return(code, offset, retval):
    log.append(("return", code.co_name, retval))


def on_yield(code, offset, retval):
    log.append(("yield", code.co_name, retval))


def on_line(code, line):
    log.append(("line", code.co_name, line - code.co_firstlineno))


def on_raise(code, offset, exc):
    log.append(("raise", code.co_name, type(exc).__name__))


def on_handled(code, offset, exc):
    log.append(("handled", code.co_name, type(exc).__name__))


def on_unwind(code, offset, exc):
    log.append(("unwind", code.co_name, type(exc).__name__))


def thrower():
    raise ValueError("boom")


def catcher():
    try:
        thrower()
    except ValueError:
        return "ok"


def gen():
    yield 1
    yield 2


def consume():
    total = 0
    for v in gen():
        total += v
    return total


def work():
    catcher()
    consume()


print(mon.get_tool(TOOL))
mon.use_tool_id(TOOL, "demo")
print(mon.get_tool(TOOL))
mon.register_callback(TOOL, E.PY_START, on_start)
mon.register_callback(TOOL, E.PY_RESUME, on_resume)
mon.register_callback(TOOL, E.PY_RETURN, on_return)
mon.register_callback(TOOL, E.PY_YIELD, on_yield)
mon.register_callback(TOOL, E.LINE, on_line)
mon.register_callback(TOOL, E.RAISE, on_raise)
mon.register_callback(TOOL, E.EXCEPTION_HANDLED, on_handled)
mon.register_callback(TOOL, E.PY_UNWIND, on_unwind)
mon.set_events(TOOL, E.PY_START | E.PY_RESUME | E.PY_RETURN | E.PY_YIELD | E.LINE | E.RAISE | E.EXCEPTION_HANDLED | E.PY_UNWIND)
print(mon.get_events(TOOL) == (E.PY_START | E.PY_RESUME | E.PY_RETURN | E.PY_YIELD | E.LINE | E.RAISE | E.EXCEPTION_HANDLED | E.PY_UNWIND))
work()
mon.set_events(TOOL, E.NO_EVENTS)
mon.free_tool_id(TOOL)
for entry in log:
    if entry[1] in ("work", "catcher", "thrower", "gen", "consume"):
        print(entry)
print(mon.get_tool(TOOL))
