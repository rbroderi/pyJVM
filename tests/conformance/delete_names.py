value = None
print(value is None)
del value
try:
    print(value)
except NameError:
    print('global deleted')
try:
    del value
except NameError:
    print('global missing')
def simple(flag):
    if flag:
        local = 10
    try:
        del local
        print('deleted local')
        print(local)
    except UnboundLocalError:
        print('unbound local')
simple(True)
simple(False)
def factory():
    cell = 7
    def read():
        return cell
    def remove():
        nonlocal cell
        del cell
    def replace():
        nonlocal cell
        cell = 11
    remove()
    try:
        read()
    except NameError:
        print('empty cell')
    replace()
    print(read())
factory()
class Test:
    def local(self):
        x = 4
        del x
        try:
            return x
        except UnboundLocalError:
            return 'method unbound'
print(Test().local())
def exposed():
    return 1
del exposed
try:
    exposed()
except NameError:
    print('function deleted')
class Removed:
    pass
del Removed
try:
    Removed()
except NameError:
    print('class deleted')
a, b = 1, 2
del (a, b)
try:
    a
except NameError:
    print('tuple deleted')
