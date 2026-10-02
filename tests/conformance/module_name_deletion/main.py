import helper
print(helper.value, helper.function(), helper.Type.__name__)
helper.clear()
for name in ['value', 'function', 'Type']:
    print(name not in helper.__dict__)
    try:
        getattr(helper, name)
    except AttributeError:
        print(name, 'deleted')
