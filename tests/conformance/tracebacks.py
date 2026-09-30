def leaf():
    raise ValueError("boom")

def middle():
    leaf()

def outer():
    try:
        middle()
    except ValueError as e:
        tb = e.__traceback__
        while tb is not None:
            print(tb.tb_frame.f_code.co_name)
            print(tb.tb_frame.f_code.co_firstlineno)
            tb = tb.tb_next

outer()
