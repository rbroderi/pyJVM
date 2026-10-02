s = slice(1, 7, 2)
print(s.start, s.stop, s.step, isinstance(s, slice), type(s) is slice)
print(list(range(10))[s], 'abcdefgh'[s], bytes(range(10))[s])
print(slice(4).start, slice(4).stop, slice(4).step)
print(slice(1, 2) == slice(1, 2, None), hash(slice(1, 2, 3)) == hash(slice(1, 2, 3)))
try:
    slice()
except TypeError:
    print('requires arguments')
try:
    slice(start=1)
except TypeError:
    print('no keywords')
