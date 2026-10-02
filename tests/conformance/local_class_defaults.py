def decorator(cls):
    cls.decorated = True
    return cls

def make(value):
    @decorator
    class Local:
        def __init__(self, a, b, c, d, e, *, flag=value):
            self.args = a, b, c, d, e, flag
        def total(self, a, b, c, d, e, *, extra=value):
            return a + b + c + d + e + extra
        def nested(self):
            def inner():
                return value
            return inner
    return Local

C = make(7)
c = C(1, 2, 3, 4, 5)
print(c.args, c.total(1, 2, 3, 4, 5), c.total(1, 2, 3, 4, 5, extra=9))
print(c.total(*(1, 2, 3, 4, 5)), c.nested()(), C.decorated)
