class Counter:
    def __init__(self):
        self.i = 0
    def __aiter__(self):
        return self
    async def __anext__(self):
        if self.i >= 2:
            raise StopAsyncIteration
        x = self.i
        self.i += 1
        return x

async def f():
    it = aiter(Counter())
    return [await anext(it), await anext(it)]

c=f()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
