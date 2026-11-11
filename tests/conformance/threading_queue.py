# queue module on top of threads and the native _queue.SimpleQueue.
import queue, threading

q = queue.Queue()
for i in range(3): q.put(i)
print(q.qsize(), q.empty(), q.get(), q.get_nowait(), q.get(), q.empty())
try:
    q.get_nowait()
except queue.Empty:
    print("Empty")

bq = queue.Queue(maxsize=2)
bq.put(1); bq.put(2)
print(bq.full())
try:
    bq.put_nowait(3)
except queue.Full:
    print("Full")
try:
    bq.put(3, timeout=0.01)
except queue.Full:
    print("Full timeout")

lq = queue.LifoQueue()
for i in range(4): lq.put(i)
print([lq.get() for _ in range(4)])
pq = queue.PriorityQueue()
for x in (5, 1, 4, 2): pq.put(x)
print([pq.get() for _ in range(4)])

sq = queue.SimpleQueue()
sq.put("a"); sq.put_nowait("b")
print(sq.qsize(), sq.empty(), sq.get(), sq.get(block=False), sq.empty())
try:
    sq.get(timeout=0.01)
except queue.Empty:
    print("SimpleQueue Empty")
try:
    sq.get(timeout=-1)
except ValueError as exc:
    print("ValueError", exc)

# producers and consumers with task_done / join
work = queue.Queue()
done = queue.Queue()
def consumer():
    while True:
        item = work.get()
        if item is None:
            work.task_done()
            return
        done.put(item * item)
        work.task_done()
cs = [threading.Thread(target=consumer) for _ in range(3)]
for c in cs: c.start()
for i in range(30): work.put(i)
for _ in cs: work.put(None)
work.join()
for c in cs: c.join()
res = []
while not done.empty(): res.append(done.get())
print(sorted(res) == [i * i for i in range(30)])

# blocking get is woken by a later put
s = queue.SimpleQueue()
got = []
t = threading.Thread(target=lambda: got.append(s.get()))
t.start()
s.put("late")
t.join()
print(got)
