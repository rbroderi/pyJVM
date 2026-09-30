def leaf():
    x = 1
    raise ValueError('boom')

def middle():
    leaf()

def outer():
    try:
        middle()
    except ValueError as e:
        tb=e.__traceback__
        while tb is not None:
            print(tb.tb_frame.f_code.co_name, tb.tb_lineno)
            tb=tb.tb_next
outer()
