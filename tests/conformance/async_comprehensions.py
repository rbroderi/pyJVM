class Counter:
    def __init__(self, n):
        self.i=0
        self.n=n
    def __aiter__(self):
        return self
    async def __anext__(self):
        if self.i >= self.n:
            raise StopAsyncIteration
        value=self.i
        self.i += 1
        return value

async def double(x):
    return x * 2

async def run():
    factor=3
    a=[await double(x) * factor async for x in Counter(5) if x % 2 == 0]
    b={x async for x in Counter(4) if x != 1}
    c={x: x * factor async for x in Counter(4) if x > 1}
    return [a, sorted(b), c]

c=run()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
