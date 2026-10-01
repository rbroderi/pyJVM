events=[]

class Base:
    def __init_subclass__(cls, flag=None, **kw):
        events.append([cls.__name__, flag, len(kw)])

class Child(Base, flag=9):
    pass

print(events)
