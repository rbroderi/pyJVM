class C:
    async def add(self, x, *, y=2):
        return x + y

async def outer():
    c=C()
    return await c.add(5, y=7)

coro=outer()
try:
    coro.send(None)
except StopIteration as e:
    print(e.value)
