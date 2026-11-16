# SPDX-License-Identifier: GPL-3.0-or-later
import os

# _overlapped events and FormatMessage (Windows only).
print(os.name)

if os.name == "nt":
    import _overlapped as o
    import _winapi

    def state(h):
        return _winapi.WaitForSingleObject(h, 0) == _winapi.WAIT_OBJECT_0

    # manual-reset event: stays signalled
    ev = o.CreateEvent(None, True, False, None)
    print(state(ev))
    o.SetEvent(ev)
    print(state(ev), state(ev))
    o.ResetEvent(ev)
    print(state(ev))
    _winapi.CloseHandle(ev)

    # auto-reset event: a successful wait resets it
    ev = o.CreateEvent(None, False, True, None)
    print(state(ev), state(ev))
    o.SetEvent(ev)
    print(state(ev), state(ev))
    _winapi.CloseHandle(ev)

    # a named event
    ev = o.CreateEvent(None, True, True, "pyjvm-conf-event-%d" % os.getpid())
    print(state(ev))
    _winapi.CloseHandle(ev)

    # the same through _winapi
    ev = _winapi.CreateEventW(0, True, False, None)
    print(state(ev))
    _winapi.SetEvent(ev)
    print(state(ev))
    _winapi.ResetEvent(ev)
    print(state(ev))
    _winapi.CloseHandle(ev)

    try:
        o.CreateEvent(0, True, False, None)
    except ValueError as e:
        print("ValueError", e)

    for f in (o.SetEvent, o.ResetEvent, _winapi.SetEvent, _winapi.ResetEvent):
        try:
            f(0)
        except OSError as e:
            print(type(e).__name__, e.winerror)

    # the event of an Overlapped made without arguments is a manual-reset, unsignalled event
    ov = o.Overlapped()
    print(ov.event != 0, state(ov.event), state(ov.event))
    o.SetEvent(ov.event)
    print(state(ov.event))
    del ov

    # an event passed in is used as is
    ev = o.CreateEvent(None, True, True, None)
    ov = o.Overlapped(ev)
    print(ov.event == ev, state(ev))

    print(o.FormatMessage(5))
    print(o.FormatMessage(2))
    print(o.FormatMessage(997))
    print(o.FormatMessage(0xF0000000))
    print(o.FormatMessage(0))
