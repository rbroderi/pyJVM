try:
    raise ValueError('inner')
except ValueError as inner:
    try:
        raise TypeError('outer') from inner
    except TypeError as outer:
        print(type(outer.__cause__))
        print(outer.__cause__.args)
        print(outer.__suppress_context__)
