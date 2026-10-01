events=[]

class C:
    def __new__(cls, value):
        events.append("new:"+str(value))
        if value < 0:
            return 99
        return object.__new__(cls)

    def __init__(self, value):
        events.append("init:"+str(value))
        self.value=value

a=C(4)
b=C(-1)
print(a.value)
print(b)
print(events)
print(a.__class__ is C)
print(a.__dict__)
