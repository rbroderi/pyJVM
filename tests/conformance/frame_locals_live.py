def f(a):
    b = 2
    try:
        raise ValueError('x')
    except ValueError as e:
        tb = e.__traceback__
        b = 9
        print(tb.tb_frame.f_locals['a'], tb.tb_frame.f_locals['b'])
        print('tb' in tb.tb_frame.f_locals)
f(5)
