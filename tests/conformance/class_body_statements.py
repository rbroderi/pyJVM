# Class suites execute in order; bindings live in the prepared namespace.
value = 'global'
class Suite:
    seen = value
    values = []
    for index in range(5):
        try:
            if index == 1: continue
            if index == 4: break
            values.append(index)
        finally:
            values.append(-index)
    else:
        values.append(99)
    left, right = (2, 3)
    alias = left = left + right
    del right
    try:
        del missing
    except NameError as error:
        removed = True
    try:
        error
    except NameError:
        cleaned = True
    if left == 5:
        def method(self): return value
    while left < 7:
        left += 1
    class Inner:
        value = 'inner'
        def method(self): return value
    value = 'class'
print(Suite.seen, Suite.value, Suite.values, Suite.alias, Suite.left)
print(Suite.removed, Suite.cleaned, hasattr(Suite, 'right'), hasattr(Suite, 'error'))
print(Suite().method(), Suite.Inner().method(), Suite.Inner.__qualname__)

def make(captured):
    changed = 'before'
    class Outer:
        nonlocal changed
        changed = 'after'
        class Inner:
            first = captured
            def get(self): return captured
        captured = 'masked'
    return Outer, changed
outer, changed = make('closure')
print(outer.captured, outer.Inner.first, outer.Inner().get(), changed)
print(outer.Inner.__qualname__)

class GlobalBinding:
    global created
    created = 'created'
print(created, hasattr(GlobalBinding, 'created'))

# An enclosing loop's cleanup must survive an inlined class suite.
for index in range(3):
    try:
        class LoopClass:
            for item in range(3):
                if item == 1: break
            result = item
        if index < 2: continue
        print(index, LoopClass.result)
    finally:
        print('outer cleanup', index)

try:
    raise ValueError('original')
except ValueError:
    try:
        class Reraise:
            raise
    except ValueError as error:
        print(str(error))
