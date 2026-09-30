def gen():
    yield 1
    raise ValueError("boom")

def run():
    g = gen()
    print(next(g))
    try:
        next(g)
    except ValueError as e:
        tb = e.__traceback__
        while tb is not None:
            print(tb.tb_frame.f_code.co_name)
            tb = tb.tb_next

run()
