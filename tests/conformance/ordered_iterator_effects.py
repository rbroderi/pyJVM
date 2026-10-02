events = []
def values():
    for value in [3, 1, 2]:
        events.append(('next', value))
        yield value
def key(value):
    events.append(('key', value))
    return -value
print(min(values(), key=key), events)
events.clear()
print(sorted(values(), key=key), events)
# A key is evaluated once for every item, after sorted consumes its input.
events.clear()
class Reverse:
    def __bool__(self):
        events.append('reverse')
        return True
print(sorted(values(), key=key, reverse=Reverse()), events)
class CallableKey:
    def __call__(self, value): return -value
print(max([1, 2], key=CallableKey()))
class FailedKey:
    def __call__(self, value): raise ValueError('key failed')
for function in [min, max, sorted]:
    print(function([], key=FailedKey(), **({'default': 8} if function is not sorted else {})))
    try:
        function([1], key=FailedKey())
    except ValueError as error:
        print(str(error))
def broken():
    yield 1
    raise RuntimeError('iteration failed')
for function in [min, max, sorted]:
    try:
        function(broken())
    except RuntimeError as error:
        print(str(error))
events.clear()
try:
    sorted(values(), invalid=1)
except TypeError:
    print(events)
print(min is min, max is max, sorted is sorted, hex is hex, len is len)
