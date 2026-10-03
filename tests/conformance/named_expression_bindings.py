def conditional(flag):
    if flag and (value := 4): print(value)
    return value
for flag in [True, False]:
    try: print(conditional(flag))
    except UnboundLocalError: print('short circuit')
def comprehension(values):
    result = [last := item for item in values]
    return (result, last)
for values in [[1,2,3], []]:
    try: print(comprehension(values))
    except UnboundLocalError: print('empty comprehension')
def outer():
    value = 5
    def inner(flag):
        if flag and (value := 7): pass
        return value
    try: inner(False)
    except UnboundLocalError: print('nested local')
    print(value)
outer()
