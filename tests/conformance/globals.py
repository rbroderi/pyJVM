BASE = 40

def add_base(x):
    return BASE + x

print(add_base(2))

counter = 0

def bump():
    global counter
    counter += 1
    return counter

print(bump(), bump(), counter)

class C:
    def __init__(self, x):
        self.x = x
    def value(self):
        return self.x + BASE

c = C(2)
print(c.value())
