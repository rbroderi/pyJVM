for value in ('abc', 'aé€🙂', '', None, True, 123, ['x', 2]):
    for template in ('%s', '%r', '%a', '[%8s]', '[%-8s]', '[%.2s]', '[%8.2r]', '%+05s', '%%:%s:%%'):
        print(template % value)
for value in ('🙂', 'a', 0, 65, 0x10ffff, True):
    for template in ('%c', '[%4c]', '[%-4c]', '%.0c'):
        print(repr(template % value))
method = '[%s]' .__mod__
print(method('call'), str.__mod__('%s', 'type call'))
value = '%s/%r'
value %= ('left', 'right')
print(value)
class Display:
    def __str__(self): return 'stré'
    def __repr__(self): return 'repr🙂'
print('%s %r %a' % (Display(), Display(), Display()))
class OnlyRepr:
    def __repr__(self): return 'only repr'
print('%s %r' % (OnlyRepr(), OnlyRepr()))
