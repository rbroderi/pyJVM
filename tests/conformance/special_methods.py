class Box:
    def __init__(self, value):
        self.value = value
    def __str__(self):
        return 'Box:' + str(self.value)
    def __repr__(self):
        return 'Box(' + str(self.value) + ')'
    def __len__(self):
        return self.value
    def __bool__(self):
        return self.value > 0
    def __add__(self, other):
        return Box(self.value + other.value)
    def __eq__(self, other):
        return self.value == other.value
    def __lt__(self, other):
        return self.value < other.value

b = Box(3)
print(str(b), repr(b), len(b), bool(b))
print((b + Box(4)).value)
print(b == Box(3), b < Box(4))

class Bag:
    def __init__(self):
        self.data = [10, 20, 30]
    def __getitem__(self, i):
        return self.data[i]
    def __setitem__(self, i, value):
        self.data[i] = value
    def __contains__(self, value):
        return value in self.data

bag = Bag()
print(bag[1], 20 in bag)
bag[1] = 99
print(bag[1], 20 in bag)

class Counter:
    def __init__(self, n):
        self.i = 0
        self.n = n
    def __iter__(self):
        return self
    def __next__(self):
        if self.i >= self.n:
            raise StopIteration()
        value = self.i
        self.i = self.i + 1
        return value

print(list(Counter(4)))
