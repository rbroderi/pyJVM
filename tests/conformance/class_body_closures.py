class Root:
    def name(self): return 'root'

def factory(value):
    class Outer(Root):
        if value:
            def name(self): return super().name() + ':outer'
        else:
            def name(self): return 'zero'
        class Inner(Root):
            def name(self): return super().name() + ':inner'
            def owner(self): return __class__
            def captured(self): return value
        def owner(self): return __class__
    return Outer
first, second = factory(1), factory(2)
print(first().name(), first.Inner().name(), first.Inner().captured())
print(first().owner() is first, first.Inner().owner() is first.Inner)
print(first.Inner is second.Inner, second.Inner().owner() is second.Inner)
print(factory(0)().name())

# Bindings inside suites still mask a closure before they are assigned.
def mask(value):
    class Masked:
        try:
            before = value
        except NameError:
            before = 'missing'
        if False:
            value = 'unreachable'
        try:
            raise ValueError('handled')
        except ValueError:
            def method(self): return value
    return Masked
masked = mask('closure')
print(masked.before, masked().method())

# Nonlocals and globals are declarations for the whole class suite.
global_value = 'module'
def declarations():
    captured = 'closure'
    class Declared:
        global global_value
        nonlocal captured
        old = captured
        captured = 'changed'
        global_value = 'updated'
        del captured
        try:
            captured
        except NameError:
            deleted = True
    try:
        captured
    except UnboundLocalError:
        missing = True
    return Declared, missing
result, missing = declarations()
print(result.old, result.deleted, missing, global_value)
print(hasattr(result, 'captured'), hasattr(result, 'global_value'))
