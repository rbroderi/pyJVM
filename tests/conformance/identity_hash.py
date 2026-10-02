for value in [0, 1, -1, -(2**100), 2**100, 0.5, -0.5, 1.0, float('inf'), float('-inf')]:
    print(hash(value))
print(hash(True) == hash(1) == hash(1.0))
print(hash((1, 2)) == hash((1.0, 2.0)))
print(hash('abc') == hash(b'abc'), hash(b'abc') == hash(bytes([97, 98, 99])))
obj = []
other = []
print(id(obj) == id(obj), id(obj) != id(other), id(None) == id(None))
for value in [[], {}, set(), bytearray()]:
    try:
        hash(value)
    except TypeError:
        print('unhashable')
class Key:
    def __hash__(self):
        return -1
class Large:
    def __hash__(self):
        return 2**100
class Bad:
    def __hash__(self):
        return 'wrong'
class Equal:
    def __eq__(self, other):
        return NotImplemented
print(hash(Key()), hash(Large()) == hash(2**100))
for value in [Bad(), Equal()]:
    try:
        hash(value)
    except TypeError:
        print('class unhashable')
class Plain:
    pass
p = Plain()
print(hash(p) == hash(p))

for text in ['', 'abcdefghi', 'é', 'Ā', '😀', '\ud800']:
    print(hash(text))
for raw in [b'', b'abc', bytes(range(40))]:
    print(hash(raw))
print(hash(memoryview(b'abc')) == hash(b'abc'))
try:
    hash(memoryview(bytearray(b'abc')))
except ValueError:
    print('writable rejected')
