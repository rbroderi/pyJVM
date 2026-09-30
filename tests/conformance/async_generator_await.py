def pause_iter():
    value = yield "pause"
    return value

class Pause:
    def __await__(self):
        return pause_iter()

async def agen():
    value = await Pause()
    yield value

async def take(g):
    return await anext(g)

g=agen()
c=take(g)
print(c.send(None))
try:
    c.send(7)
except StopIteration as e:
    print(e.value)
