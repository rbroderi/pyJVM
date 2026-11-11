import gc
import weakref


class C:
    def __init__(self, n):
        self.n = n

    def __hash__(self):
        return hash(self.n)

    def __eq__(self, other):
        return isinstance(other, C) and other.n == self.n

    def method(self):
        return self.n


def collect(until=lambda: False):
    for _ in range(20):
        gc.collect()
        if until():
            return


def show_wvd(wvd, objs):
    # in a helper so no compiler temporary of the caller pins a value
    print(len(wvd), sorted(wvd.keys()), wvd[3] is objs[3], wvd.get(9), 2 in wvd)
    print(sorted(v.n for v in wvd.values()), sorted(k for k, v in wvd.items()))


def make_wm(c):
    return weakref.WeakMethod(c.method)


def show_wm(wm, c):
    print(wm()(), wm() == c.method)


def containers():
    # WeakValueDictionary
    objs = [C(i) for i in range(5)]
    wvd = weakref.WeakValueDictionary()
    for o in objs:
        wvd[o.n] = o
    del o
    show_wvd(wvd, objs)
    del objs[4]
    collect(lambda: len(wvd) == 4)
    print(len(wvd), 4 in wvd)
    try:
        wvd[4]
    except KeyError as e:
        print("KeyError", e)
    print(wvd.pop(0).n, wvd.setdefault(10, objs[0]) is objs[0], len(wvd))
    r = wvd.valuerefs()
    print(len(r), weakref.ref(objs[0]) in r)

    # WeakKeyDictionary
    keys = [C(i) for i in range(4)]
    wkd = weakref.WeakKeyDictionary()
    for k in keys:
        wkd[k] = k.n * 10
    del k
    print(len(wkd), wkd[keys[2]], keys[1] in wkd, C(99) in wkd)
    del keys[3]
    collect(lambda: len(wkd) == 3)
    print(len(wkd), sorted(wkd.values()))
    print(sorted(k.n for k in wkd))
    try:
        wkd[1] = 2
    except TypeError as e:
        print("TypeError", e)

    # WeakSet
    items = [C(i) for i in range(4)]
    ws = weakref.WeakSet(items)
    print(len(ws), items[0] in ws, C(7) in ws)
    ws.add(items[0])
    print(len(ws))
    ws.discard(items[1])
    print(sorted(i.n for i in ws))
    del items[2]
    collect(lambda: len(ws) == 2)
    print(sorted(i.n for i in ws))
    ws2 = weakref.WeakSet(items[:1])
    print(sorted(i.n for i in ws | ws2), sorted(i.n for i in ws & ws2), ws2 <= ws)

    # WeakMethod
    c = C(8)
    wm = make_wm(c)
    show_wm(wm, c)
    print(wm == make_wm(c), hash(wm) == hash(make_wm(c)))
    # collection of the referent is not asserted: WeakMethod's callback closure pins its enclosing
    # environment on this runtime (see README, weakref section)

    # KeyedRef via the dictionaries
    holder = C(1)
    kr = weakref.KeyedRef(holder, None, "k")
    print(kr.key, kr() is holder)



containers()


# finalize
log = []
t = C(1)
f = weakref.finalize(t, log.append, "fin")
print(f.alive, f.peek()[2:], weakref.finalize.atexit is not None)
del t
collect(lambda: not f.alive)
print(f.alive, log)
print(f())
t2 = C(2)
f2 = weakref.finalize(t2, log.append, "explicit")
f2()
print(log, f2.alive)
t3 = C(3)
f3 = weakref.finalize(t3, log.append, "detached")
print(f3.detach()[0] is t3, f3.alive)
t4 = C(4)
f4 = weakref.finalize(t4, print, "finalize ran at exit")
