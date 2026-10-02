events = []
class Meta(type):
    pass
class Base(metaclass=Meta):
    def value(self):
        return 4
    def __init_subclass__(cls, *, tag):
        cls.tag = tag

def choose(label, value):
    events.append(label)
    return value

def make(number):
    class Child(choose("base", Base), tag=choose("tag", number), metaclass=choose("meta", Meta)):
        def value(self):
            return super().value() + number
    return Child

A = make(5)
B = make(10)
print(events, A.tag, B.tag, A().value(), B().value(), type(A) is Meta)
