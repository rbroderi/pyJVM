functions = [bin, oct, hex]
for function in functions:
    for value in [0, 1, -1, True, -(2**63), 2**100, -(2**100)]:
        print(function(value))
    for value in [1.0, None, '123', []]:
        try:
            function(value)
        except TypeError:
            print('TypeError')
class Index:
    def __index__(self): return -(2**80)
class InvalidIndex:
    def __index__(self): return 1.5
class IntegerOnly:
    def __int__(self): return 10
for function in functions:
    print(function(Index()))
    for value in [InvalidIndex(), IntegerOnly()]:
        try:
            function(value)
        except TypeError:
            print('TypeError')
    for args, kwargs in [((), {}), ((1, 2), {}), ((), {'number': 1})]:
        try:
            function(*args, **kwargs)
        except TypeError:
            print('TypeError')
class Namespace:
    value = hex(255)
print(Namespace.value)
