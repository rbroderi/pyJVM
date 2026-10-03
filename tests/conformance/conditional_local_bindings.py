value = 'global'
def branch(flag):
    if flag: value = 7
    return value
for flag in [True, False]:
    try: print(branch(flag))
    except UnboundLocalError: print('local unbound')
def early():
    print(value)
    value = 1
try: early()
except UnboundLocalError: print('shadows global before assignment')
def loop(values):
    for item in values: pass
    return item
for values in [[1,2], []]:
    try: print(loop(values))
    except UnboundLocalError: print('empty loop')
def exception(flag):
    try:
        if flag: raise ValueError('caught')
    except ValueError as error:
        print(str(error))
    return error
for flag in [True, False]:
    try: exception(flag)
    except UnboundLocalError: print('exception local unbound')
