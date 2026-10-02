def make():
    error = 'original'
    try:
        raise ValueError('caught')
    except ValueError as error:
        def read():
            return error
        return read
f = make()
try:
    f()
except NameError:
    print('return cleared cell')
try:
    raise RuntimeError('outer')
except RuntimeError as caught:
    print(str(caught))
    del caught
try:
    caught
except NameError:
    print('handler cleared')
def run():
    try:
        raise ValueError('yielding')
    except ValueError as error:
        def read():
            return error
        yield read
    try:
        error
    except UnboundLocalError:
        yield 'generator cleared'
g = run()
f = next(g)
print(str(f()))
print(next(g))
try:
    f()
except NameError:
    print('generator cell cleared')
def erase_global():
    global data
    del data
data = 'present'
erase_global()
try:
    data
except NameError:
    print('global statement cleared')

g = run()
f = next(g)
g.close()
try:
    f()
except NameError:
    print('close cleared cell')
