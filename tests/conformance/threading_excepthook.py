# Uncaught exceptions in threads go through threading.excepthook.
import threading

captured = []
def hook(args):
    captured.append((args.exc_type.__name__, str(args.exc_value), args.thread.name, args.exc_traceback is not None))
old = threading.excepthook
threading.excepthook = hook

def boom():
    raise ValueError("bad value")
t = threading.Thread(target=boom, name="boomer")
t.start(); t.join()
print(captured)

def quiet():
    raise SystemExit(1)
t = threading.Thread(target=quiet, name="quiet")
t.start(); t.join()
print(len(captured))

print(threading.excepthook is hook, threading.__excepthook__ is old)
threading.excepthook = old
print(hasattr(threading, "ExceptHookArgs"))

seen = []
def other_hook(args):
    seen.append("hook")
threading.excepthook = other_hook
t = threading.Thread(target=boom); t.start(); t.join()
print(seen, t.is_alive())
