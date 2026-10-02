values = [1, 2]
original = values
values += [3]
values *= 2
print(values, values is original)
array = bytearray(b'ab')
original = array
try:
    array += array
except BufferError:
    print('self buffer rejected')
array += b'ab' 
array *= 2
print(array, array is original)
array *= 0
print(array, array is original)
class Power:
    def __ipow__(self, other):
        return NotImplemented
    def __pow__(self, other):
        return other + 1
p = Power()
p **= 3
print(p)
class Add:
    def __iadd__(self, other):
        return NotImplemented
    def __add__(self, other):
        return other + 2
a = Add()
a += 3
print(a)
