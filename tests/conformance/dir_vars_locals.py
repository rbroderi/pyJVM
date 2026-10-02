def function(argument):
    local = 2
    print(dir())
    first = vars()
    print(first == {'argument': 1, 'local': 2})
    first['local'] = 99
    first['injected'] = 0
    print(local, 'injected' in vars())
    del local
    print('local' in dir())
    alias = vars
    print(alias()['argument'])
function(1)
def enclosing():
    captured = 7
    def nested(value):
        print(captured, value)
        print('captured' in dir(), 'captured' in vars(), vars()['value'])
    nested(8)
enclosing()
class Container:
    x = 1
    print('x' in dir(), vars()['x'])
    namespace = vars()
    namespace['y'] = 2
print(Container.y)
global_value = 0
def writer():
    global global_value
    global_value = 1
    print('global_value' in vars(), 'global_value' in dir())
writer()
