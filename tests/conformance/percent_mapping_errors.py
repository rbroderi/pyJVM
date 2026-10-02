values = {'name':'é🙂', 'n':17, '(key)':3, '':4}
for template in ('%(name)s/%(n)#06x', '%((key))d', '%()d', '%(name).1s', '%s %(n)d', '%(n)d %% %(n)x'):
    print(template % values)
class Mapping:
    def __getitem__(self, key):
        print('lookup', key)
        return {'x':17, 'name':'mapped'}[key]
print('%(x)04d/%(name)s' % Mapping())
for value in ({}, [], ()):
    print(repr('plain %%' % value))
for template,args in (('%',()),('%(',{}),('%(x)s',()),('%q',1),('%s %s',(1,)),('%s',(1,2)),('',1),('%d','3'),('%f','3'),('%x',1.2),('%c','ab'),('%c',-1),('%c',0x110000),('%*s',('3','a')),('%.*s',('3','a')),('%*s',3),('%(x)*s',{'x':'a'}),('%(x)s %s',{'x':'a'}),('%(missing)s',{}),('%.2147483648f',1),('%999999999999999999999s','a'),('%.*f',(2**40,1)),('%d',float('nan')),('%d',float('inf')),('%f',2**2000)):
    try:
        print('value',template % args)
    except (TypeError, ValueError, KeyError, OverflowError) as error:
        print(type(error).__name__,str(error))
class Raising:
    def __int__(self): raise RuntimeError('int callback')
    def __index__(self): raise RuntimeError('index callback')
    def __float__(self): raise RuntimeError('float callback')
    def __str__(self): raise RuntimeError('str callback')
    def __repr__(self): raise RuntimeError('repr callback')
for template in ('%d','%x','%f','%s','%r'):
    try: print(template % Raising())
    except RuntimeError as error: print(str(error))
class BadNumber:
    def __int__(self): return 3.5
    def __index__(self): return 3.5
for template in ('%d','%x','%c'):
    try: print(template % BadNumber())
    except TypeError as error: print(str(error))
for template,args in (('%s %d',('a','bad')),('%(x)d',{'x':'bad'}),('%5%',1),('x🙂%q',1),('%\n',1),('%é',1),('%🙂',1),('%\x00',1)):
    try: print(template % args)
    except (TypeError,ValueError) as error: print(str(error))
class BadReal:
    def __float__(self): return 1
class TypeFailure:
    def __float__(self): raise TypeError('callback')
    def __int__(self): raise TypeError('callback')
    def __index__(self): raise TypeError('callback')
for template,value in (('%f',BadReal()),('%f',TypeFailure()),('%d',TypeFailure()),('%x',TypeFailure())):
    try: print(template % value)
    except TypeError as error: print(str(error))
