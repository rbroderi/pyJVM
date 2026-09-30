def g():
    try:
        raise ValueError("boom")
    except ValueError:
        yield "caught"
        raise

it = g()
print(next(it))
try:
    next(it)
except ValueError as e:
    print(type(e), e.args)
