import gc
import weakref


def collect(until=lambda: False):
    for _ in range(20):
        gc.collect()
        if until():
            return


log = []


class A:
    def __init__(self, name):
        self.name = name

    def __del__(self):
        log.append("del " + self.name)


a = A("a")
del a
collect(lambda: log)
print(log)


# cycles are collected and finalized
class B:
    def __init__(self, name):
        self.name = name
        self.me = self

    def __del__(self):
        log.append("del " + self.name)


log.clear()
B("cyc")
collect(lambda: log)
print(log)


# PEP 442: __del__ runs once; resurrection keeps the object alive
saved = []


class R:
    def __del__(self):
        log.append("R del")
        saved.append(self)


log.clear()
R()
collect(lambda: saved)
print(log, len(saved))
saved.clear()
collect()
print(log)


# __del__ on subclass, super().__del__, exception in __del__ is not fatal
class Base:
    def __del__(self):
        log.append("base")


class Derived(Base):
    def __del__(self):
        log.append("derived")
        super().__del__()


log.clear()
Derived()
collect(lambda: len(log) >= 2)
print(log)


class Bad:
    def __del__(self):
        raise RuntimeError("ignored")


Bad()
collect()
print("after bad")


# weakref callbacks and __del__ together
log.clear()


class W:
    def __del__(self):
        log.append("W del")


w = W()
wr = weakref.ref(w, lambda r: log.append("callback"))
del w
collect(lambda: len(log) >= 2)
print(sorted(log), wr())


# a global dropped at once: the object is finalized only after its last reference is gone
log.clear()
keep = A("keep")
other = keep
del keep
collect()
print(log)
del other
collect(lambda: log)
print(log)
