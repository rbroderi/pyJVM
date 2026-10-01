events=[]

class Descriptor:
    def __set_name__(self, owner, name):
        self.owner=owner
        self.name=name
        events.append("set:"+owner.__name__+":"+name)

    def __get__(self, obj, owner):
        return self.name

class Base:
    def __init_subclass__(cls):
        events.append("sub:"+cls.__name__+":"+cls.field)

class Child(Base):
    field=Descriptor()

print(events)
print(Child.field)
print(Child.__name__)
print(Child.__qualname__)
print(Child.__module__)
print(Child.__bases__)
print(Child.__mro__)
