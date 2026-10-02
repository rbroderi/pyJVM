events = []
def mark(value):
    events.append(value)
    return value
def decorate(function):
    events.append('decorate')
    return staticmethod(function)
class Ordered:
    first = mark('first')
    @decorate
    def pick(value=mark('default')): return value
    second = mark('second')
    alias = pick
    pick = 3
    result = alias()
print(events, Ordered.pick, Ordered.result, Ordered.alias())
class Replaced:
    def value(self): return 1
    value = 7
print(Replaced().value)
class Restored:
    value = 7
    def value(self): return 2
print(Restored().value())
value = 11
class ReadThenBind:
    before = value
    value = 22
    after = value
    def read(self): return value
print(ReadThenBind.before, ReadThenBind.after, ReadThenBind().read())
def factory(outer):
    class Local:
        outer_copy = outer
        @property
        def value(self): return outer
        alias = value
        def choose(self, default=outer_copy): return default
        callback = staticmethod(lambda: outer)
    return Local
one, two = factory(3), factory(8)
print(one().alias, two().value, one().choose(), two.callback())
values = [1, 2]
class Comprehension:
    values = [3, 4]
    copy = [x for x in values]
    from_global = [values for unused in [0]]
print(Comprehension.copy, Comprehension.from_global)
def local_masking(unknown):
    class Masked:
        unknown = unknown
    return Masked
try: local_masking(3)
except NameError: print('class local masks closure lookup')
def lexical_lambda(value):
    class Capture:
        value = 20
        callback = staticmethod(lambda: value)
    return Capture.callback()
print(lexical_lambda(7))
try:
    class BeforeBinding:
        value = defined_later
except NameError:
    print('global not yet bound')
defined_later = 12
def use_static(function): return staticmethod(function)
class ShadowedDecorator:
    classmethod = use_static
    @classmethod
    def answer(): return 42
print(ShadowedDecorator.answer(), ShadowedDecorator().answer())
