try:
    try:
        raise ValueError('inner')
    except ValueError:
        raise TypeError('outer')
except TypeError as e:
    print(type(e.__context__))
    print(e.__context__.args)
    print(e.__cause__)
    print(e.__suppress_context__)
