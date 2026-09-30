try:
    raise ValueError
except ValueError as e:
    print(type(e), e.args)
