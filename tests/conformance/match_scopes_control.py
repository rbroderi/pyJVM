def factory(value):
    match value:
        case captured:
            class Local:
                saved = captured
                def read(self): return captured
            def read(): return captured
    return (Local, read)
first, first_read = factory(4)
second, second_read = factory(8)
print(first.saved, first().read(), first_read())
print(second.saved, second().read(), second_read(), first is second)
class Scope:
    match 9:
        case captured:
            def read(self): return __class__.captured
print(Scope.captured, Scope().read())
for value in [0,1,2,3]:
    try:
        match value:
            case 0: continue
            case 2: break
            case _: print('loop', value)
    finally: print('finally', value)
def returned(value):
    try:
        match value:
            case 1: return 4
            case _: return 8
    finally: print('return finally')
print(returned(1), returned(2))
module_value = 0
def global_capture():
    global module_value
    match 6:
        case module_value: pass
global_capture(); print(module_value)
def enclosing():
    cell = 0
    def assign():
        nonlocal cell
        match 7:
            case cell: pass
    assign()
    return cell
print(enclosing())
class Private:
    match 8:
        case __value: pass
    def read(self): return self.__value
print(Private().read())
