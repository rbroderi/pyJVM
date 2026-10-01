events=[]

class Meta(type):
    @classmethod
    def __prepare__(mcls, name, bases):
        events.append("prepare:"+name)
        return {"prepared": 41}

    def __new__(mcls, name, bases, namespace):
        events.append("new:"+name+":"+str(namespace["prepared"]))
        namespace["added"] = 99
        return type.__new__(mcls, name, bases, namespace)

    def __init__(cls, name, bases, namespace):
        events.append("init:"+name)
        type.__init__(cls, name, bases, namespace)

    def __call__(cls, value):
        events.append("call:"+str(value))
        return type.__call__(cls, value)

class C(metaclass=Meta):
    x=1
    def __init__(self, value):
        self.value=value

c=C(5)
print(events)
print(C.x, C.added, c.value)
print(type(C) is Meta)
print(C.__class__ is Meta)
