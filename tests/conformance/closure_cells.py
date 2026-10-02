def factory(initial):
    value = initial
    def read(): return value
    def write(new):
        nonlocal value
        value = new
    return read, write
read, write = factory(10)
cell = read.__closure__[0]
print(cell is write.__closure__[0], cell.cell_contents, read())
write(20)
print(cell.cell_contents, read())
cell.cell_contents = 30
print(cell.cell_contents, read())
del cell.cell_contents
try:
    print(cell.cell_contents)
except ValueError as error:
    print(str(error))
try:
    read()
except NameError:
    print('deleted binding')
del cell.cell_contents
cell.cell_contents = None
print(read() is None)
for operation in ('set', 'delete'):
    try:
        if operation == 'set': read.__closure__ = ()
        else: del read.__closure__
    except AttributeError:
        print('readonly closure')
def uncaptured(): return 1
print(uncaptured.__closure__ is None)
class X:
    def f(self): return __class__
class Y: pass
class_cell = X.f.__closure__[0]
print(class_cell.cell_contents is X)
class_cell.cell_contents = Y
print(X().f() is Y)
def empty_binding():
    def read(): return future
    cell = read.__closure__[0]
    try:
        print(cell.cell_contents)
    except ValueError:
        print('empty before assignment')
    future = 42
    print(cell.cell_contents, read())
empty_binding()
