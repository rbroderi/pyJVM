for value in (0, 1, -1, 42, -42, True, 2**80, -(2**80)):
    for template in ('%d', '%i', '%u', '%o', '%x', '%X', '%#x', '%#X', '%#o', '%+08d', '% 08d', '%-08d', '%08.3d', '%#.0x', '%#012.6x', '%.0d'):
        print(template % value)
for value in (1.9, -1.9, 1e100):
    print('%d %i %u' % (value, value, value))
class Number:
    def __int__(self):
        print('int')
        return 42
    def __index__(self):
        print('index')
        return 109
print('%d %i %u %x %X %o %c' % (Number(), Number(), Number(), Number(), Number(), Number(), Number()))
class Indexed:
    def __index__(self): return -8
print('%d %#x %o' % (Indexed(), Indexed(), Indexed()))
for template,args in (('%*.*s',(7,2,'🙂abc')),('%*d',(-7,3)),('%.*d',(-1,7)),('%*.*d',(8,4,17)),('%*s',(True,'a'))):
    print(repr(template % args))
# Numeric modulo retains Python sign and precision behavior.
for left,right in ((7,3),(-7,3),(7,-3),(2**80,97),(7.5,2.0)):
    print(left % right)
