def pause_iter():
    value = yield "step"
    return value

class Pause:
    def __await__(self):
        return pause_iter()

class Counter:
    def __init__(self, n):
        self.i=0
        self.n=n
    def __aiter__(self):
        return self
    async def __anext__(self):
        if self.i >= self.n:
            raise StopAsyncIteration
        self.i += 1
        return await Pause()

async def collect():
    out=[]
    async for x in Counter(2):
        out.append(x)
    else:
        out.append(99)
    return out

c=collect()
print(c.send(None))
print(c.send(3))
try:
    c.send(4)
except StopIteration as e:
    print(e.value)
