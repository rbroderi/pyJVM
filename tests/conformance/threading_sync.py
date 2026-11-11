# Lock, RLock, Condition, Semaphore, Event, Barrier and Timer.
import threading, time

lock = threading.Lock()
print(lock.locked(), lock.acquire(), lock.locked(), lock.acquire(False), lock.acquire(timeout=0.01))
lock.release()
try:
    lock.release()
except RuntimeError as exc:
    print("RuntimeError", exc)
with lock:
    print("held", lock.locked())
print(lock.locked())

rlock = threading.RLock()
with rlock:
    with rlock:
        print("nested", rlock._is_owned())
print(rlock._is_owned())
try:
    rlock.release()
except RuntimeError as exc:
    print("RuntimeError", exc)

# counter guarded by a lock
counter = [0]
def bump():
    for _ in range(2000):
        with lock:
            counter[0] += 1
ts = [threading.Thread(target=bump) for _ in range(8)]
for t in ts: t.start()
for t in ts: t.join()
print(counter[0])

# Condition: producer / consumer hand-off
cond = threading.Condition()
box = []
def consumer(out):
    with cond:
        while not box:
            cond.wait()
        out.append(box.pop())
got = []
c = threading.Thread(target=consumer, args=(got,))
c.start()
with cond:
    box.append("item")
    cond.notify()
c.join()
print(got)
with cond:
    print(cond.wait(0.01))
    print(cond.wait_for(lambda: True))

# notify_all wakes everybody
ready = []
go = False
def sleeper(i):
    with cond:
        ready.append(i)
        cond.wait_for(lambda: go)
ts = [threading.Thread(target=sleeper, args=(i,)) for i in range(4)]
for t in ts: t.start()
while True:
    with cond:
        if len(ready) == 4: break
    time.sleep(0.001)
with cond:
    go = True
    cond.notify_all()
for t in ts: t.join()
print(sorted(ready))

# Semaphore / BoundedSemaphore
sem = threading.Semaphore(2)
print(sem.acquire(), sem.acquire(), sem.acquire(False), sem.acquire(timeout=0.01))
sem.release(); print(sem.acquire(False))
b = threading.BoundedSemaphore(1)
b.acquire(); b.release()
try:
    b.release()
except ValueError as exc:
    print("ValueError", exc)
try:
    threading.Semaphore(-1)
except ValueError as exc:
    print("ValueError", exc)

# Event
ev = threading.Event()
print(ev.is_set(), ev.wait(0.01))
res = []
def waiter():
    res.append(ev.wait(5))
w = threading.Thread(target=waiter); w.start()
ev.set(); w.join()
print(res, ev.is_set())
ev.clear(); print(ev.is_set())

# Barrier
n = 4
barrier = threading.Barrier(n)
order = []
def party(i):
    idx = barrier.wait()
    order.append(idx)
ts = [threading.Thread(target=party, args=(i,)) for i in range(n)]
for t in ts: t.start()
for t in ts: t.join()
print(sorted(order), barrier.parties, barrier.n_waiting, barrier.broken)
b2 = threading.Barrier(2, timeout=0.05)
try:
    b2.wait()
except threading.BrokenBarrierError:
    print("broken", b2.broken)

# Timer
fired = []
t = threading.Timer(0.01, fired.append, args=("fired",))
t.start(); t.join()
print(fired)
t2 = threading.Timer(5, fired.append, args=("never",))
t2.start(); t2.cancel(); t2.join()
print(fired)
