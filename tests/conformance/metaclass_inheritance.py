events=[]

class Meta(type):
    @classmethod
    def __prepare__(mcls, name, bases):
        events.append("prepare:"+name)
        return {}

    def __new__(mcls, name, bases, namespace):
        namespace["made_by"] = name
        events.append("new:"+name)
        return type.__new__(mcls, name, bases, namespace)

    def __init__(cls, name, bases, namespace):
        events.append("init:"+name)
        type.__init__(cls, name, bases, namespace)

class Base(metaclass=Meta):
    pass

class Child(Base):
    pass

print(events)
print(Base.made_by)
print(Child.made_by)
print(type(Child) is Meta)
print(issubclass(Meta, type))
