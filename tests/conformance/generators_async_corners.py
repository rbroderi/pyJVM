class Pause:
    def __await__(self):
        value = yield "pause"
        return value

async def f(i):
    return i

def drive(coro):
    sent = None
    steps = []
    try:
        while True:
            steps.append(coro.send(sent))
            sent = len(steps)
    except StopIteration as e:
        return steps, e.value

class aiter3:
    def __init__(self):
        self.n = 0
    def __aiter__(self):
        return self
    async def __anext__(self):
        if self.n >= 2:
            raise StopAsyncIteration
        self.n += 1
        return self.n

async def comps():
    a = [await f(i) for i in range(3)]
    b = {await f(i) for i in range(3)}
    c = {await f(i): i for i in range(3)}
    d = {i: await f(i) for i in range(3)}
    e = [s for c0 in [f("ab"), f("c")] for s in await c0]
    g = [await f(i) + await Pause() for i in range(2)]
    h = [[i + j async for i in aiter3()] for j in (10, 20)]
    k = [x for x in await f([1, 2]) if await f(x) > 1]
    return a, sorted(b), c, d, e, g, h, k

print(drive(comps()))

async def mk():
    return Pause()

async def unary_ops():
    a = -await f(3)
    b = not await f(0)
    c = (await f(1) + await f(2) * 10)
    d = f"{await f('x')}-{await f(7):>3}"
    e = await (await mk())
    return a, b, c, d, e

print(drive(unary_ops()))

class CM:
    def __enter__(self):
        print("enter")
    def __exit__(self, *a):
        print("exit", a[0])
        return False

async def with_return():
    with CM():
        await Pause()
        return "ret"

print(drive(with_return()))

async def with_loop():
    out = []
    for i in range(4):
        with CM():
            await Pause()
            if i == 1:
                continue
            if i == 3:
                break
            out.append(i)
    return out

print(drive(with_loop()))

async def genexp_arg():
    return await f(sum(x for x in [1, 2, 3]))

print(drive(genexp_arg()))

async def ag():
    gen = (await f(i) for i in range(3))
    out = []
    async for v in gen:
        out.append(v)
    return out

print(drive(ag()))

async def bad():
    return list(await f(i) for i in range(2))

try:
    drive(bad())
except TypeError as e:
    print("TypeError")
