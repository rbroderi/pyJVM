log = []
try:
    log.append('body')
finally:
    log.append('finally')
print(log)

try:
    try:
        raise ValueError('x')
    except ValueError:
        log.append('except')
finally:
    log.append('outer-finally')
print(log)

class CM:
    def __init__(self, suppress):
        self.suppress = suppress
    def __enter__(self):
        print('enter')
        return 42
    def __exit__(self, typ, value, tb):
        print('exit', typ, value)
        return self.suppress

with CM(False) as x:
    print('x', x)

try:
    with CM(True):
        raise ValueError('hidden')
    print('suppressed')
except ValueError:
    print('not suppressed')
