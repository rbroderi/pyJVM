class Counter:
    def __init__(self, n):
        self.i = 0
        self.n = n
    def __aiter__(self):
        return self
    async def __anext__(self):
        if self.i >= self.n:
            raise StopAsyncIteration
        value = self.i
        self.i += 1
        return value

async def collect():
    out=[]
    async for x in Counter(4):
        out.append(x)
    else:
        out.append(9)
    return out

c=collect()
try:
    c.send(None)
except StopIteration as e:
    print(e.value)
