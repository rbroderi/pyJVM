events = []
class Manager:
    def __enter__(self):
        events.append('enter')
        return 7
    def __exit__(self, kind, value, traceback):
        events.append('exit')
        return kind is ValueError
class ContextSuite:
    with Manager() as bound:
        for index in range(2):
            if index: break
        def get(self): return 'defined'
        raise ValueError('suppressed')
    after = bound + index
print(events, ContextSuite.bound, ContextSuite.after, ContextSuite().get())

# A failing class suite does not publish a partially constructed class.
Existing = 'original'
try:
    class Existing:
        assigned = 1
        raise ValueError('failed')
except ValueError:
    print(Existing)

try:
    later
except NameError:
    print('unbound global')
class GlobalSuite:
    global later
    later = 10
print(later)

# Inner branches keep an enclosing finally active until its suite ends.
class FinallySuite:
    order = []
    try:
        for index in range(3):
            if index == 0: continue
            if index == 1: break
        order.append('body')
    finally:
        order.append('finally')
print(FinallySuite.order)

# Exiting an inner try must not also exit the outer with around its loop.
events = []
with Manager():
    for index in range(3):
        try:
            if index == 0: continue
            break
        finally:
            events.append(index)
    events.append('body')
print(events)
