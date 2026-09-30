def pause_iter():
    value = yield "inner"
    return value

class Pause:
    def __await__(self):
        return pause_iter()

async def agen():
    sent = yield 1
    resumed = await Pause()
    yield sent + resumed

async def get_next(g):
    return await anext(g)

async def send_next(g, value):
    return await g.asend(value)

g=agen()
c=get_next(g)
try:
    c.send(None)
except StopIteration as e:
    print(e.value)

c=send_next(g, 10)
print(c.send(None))
try:
    c.send(5)
except StopIteration as e:
    print(e.value)
