events = []

class Meta(type):
    @classmethod
    def __prepare__(mcls, name, bases, **kw):
        events.append(["prepare", name, kw["flag"]])
        return {"prepared": 11}

    def __new__(mcls, name, bases, namespace, **kw):
        events.append(["new", name, namespace["prepared"], namespace["x"], kw["flag"]])
        namespace["from_new"] = kw["flag"] + 1
        return type.__new__(mcls, name, bases, namespace)

    def __init__(cls, name, bases, namespace, **kw):
        events.append(["init", name, kw["flag"]])

class C(metaclass=Meta, flag=7):
    x = 3

print(events)
print(C.x, C.prepared, C.from_new)
print(type(C) is Meta)
