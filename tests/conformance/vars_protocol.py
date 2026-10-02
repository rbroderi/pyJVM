inspect = vars
class Value: pass
value = Value()
value.x = 1
namespace = inspect(value)
print(namespace is value.__dict__, namespace)
namespace['y'] = 2
print(value.y)
del namespace['x']
print(hasattr(value, 'x'))
class Base:
    @property
    def __dict__(self): return ('custom', 3)
class Custom(Base): pass
print(vars(Custom()))
class Constant:
    __dict__ = 7
print(vars(Constant()))
class Missing:
    __slots__ = ()
for item in [None, 1, 's', [], {}, Missing()]:
    try: vars(item)
    except TypeError: print('TypeError')
class Failing:
    @property
    def __dict__(self): raise ValueError('callback')
try: vars(Failing())
except ValueError as error: print(str(error))
def function(): pass
function.extra = 4
print(vars(function), vars(function) is function.__dict__)
for invoke in [lambda: vars(1, 2), lambda: vars(object=value)]:
    try: invoke()
    except TypeError: print('signature')
class Access:
    def __getattribute__(self, name):
        if name == '__dict__': return {'custom': 8}
        return object.__getattribute__(self, name)
print(vars(Access()))
class MissingAccess:
    def __getattribute__(self, name): raise AttributeError(name)
try: vars(MissingAccess())
except TypeError: print('missing access')
