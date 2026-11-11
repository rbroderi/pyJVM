# Interpreter-wide thread settings and per-thread profiling hooks.
import sys, threading

old = sys.getswitchinterval()
sys.setswitchinterval(0.01)
print(abs(sys.getswitchinterval() - 0.01) < 1e-9)
sys.setswitchinterval(old)
try:
    sys.setswitchinterval(0)
except ValueError as exc:
    print("ValueError", exc)
try:
    sys.setswitchinterval("x")
except TypeError:
    print("TypeError")

ev, done = threading.Event(), threading.Event()
def parked():
    ev.set(); done.wait()
t = threading.Thread(target=parked); t.start(); ev.wait()
cf = sys._current_frames()
print(threading.get_ident() in cf, t.ident in cf, len(cf) >= 2)
done.set(); t.join()

events = []
def prof(frame, event, arg):
    if event == "call" and frame.f_code.co_name == "traced":
        events.append(threading.current_thread().name)
def traced(): return 1
threading.setprofile(prof)
w = threading.Thread(target=traced, name="W"); w.start(); w.join()
threading.setprofile(None)
w = threading.Thread(target=traced, name="V"); w.start(); w.join()
print(events, sys.getprofile())
