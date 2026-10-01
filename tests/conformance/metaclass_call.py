class Meta(type):
    def __call__(cls, value, **kw):
        return [cls.__name__, value, kw["extra"]]

class C(metaclass=Meta):
    pass

print(C(5, extra=8))
