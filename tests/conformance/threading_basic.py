# Thread lifecycle, identity and the enumeration helpers.
import threading

main = threading.current_thread()
print(main.name, main is threading.main_thread(), main.ident == threading.get_ident(), main.daemon)
print(threading.active_count(), threading.enumerate() == [main], main.is_alive())

results = []

def work(a, b, *, c):
    me = threading.current_thread()
    results.append((a, b, c, me.name, me.daemon, me.is_alive(), me.ident == threading.get_ident()))

t = threading.Thread(target=work, args=(1, 2), kwargs={"c": 3}, name="worker")
print(t.name, t.is_alive(), t.ident, t.daemon)
t.start()
t.join()
print(results, t.is_alive(), t.ident is not None, t.native_id is not None)

try:
    t.start()
except RuntimeError as exc:
    print("RuntimeError", exc)

try:
    threading.Thread().join()
except RuntimeError as exc:
    print("RuntimeError", exc)

d = threading.Thread(target=lambda: None, daemon=True)
print(d.daemon)
d.start(); d.join()

class Sub(threading.Thread):
    def __init__(self, n):
        super().__init__(name="sub-%d" % n)
        self.n = n
        self.value = None
    def run(self):
        self.value = self.n * self.n

subs = [Sub(i) for i in range(5)]
for s in subs: s.start()
for s in subs: s.join()
print([(s.name, s.value) for s in subs])

started = threading.Event()
release = threading.Event()
def waiter():
    started.set()
    release.wait()
w = threading.Thread(target=waiter, name="waiter")
w.start()
started.wait()
print(threading.active_count(), sorted(x.name for x in threading.enumerate()), w.is_alive())
w.join(0.01)
print(w.is_alive())
release.set()
w.join()
print(threading.active_count(), w.is_alive())

idents = set()
def record():
    idents.add(threading.get_ident())
ts = [threading.Thread(target=record) for _ in range(6)]
for x in ts: x.start()
for x in ts: x.join()
print(len(idents) >= 1)

import _thread
print(isinstance(_thread.get_ident(), int), isinstance(_thread.get_native_id(), int))
print(_thread.TIMEOUT_MAX > 1000, threading.TIMEOUT_MAX == _thread.TIMEOUT_MAX)
