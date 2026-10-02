class Top:
    def foo():
        return "top"
print(Top.foo())
try:
    Top().foo()
except TypeError:
    print("bound rejects")
def factory():
    class Local:
        def foo():
            return "local"
    return Local
C = factory()
print(C.foo())
try:
    C().foo()
except TypeError:
    print("local bound rejects")
