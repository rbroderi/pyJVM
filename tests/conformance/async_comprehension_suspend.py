def pause_iter():
    value=yield "pause"
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

async def run():
    return [x * 2 async for x in Counter(2)]

c=run()
print(c.send(None))
print(c.send(5))
try:
    c.send(7)
except StopIteration as e:
    print(e.value)
